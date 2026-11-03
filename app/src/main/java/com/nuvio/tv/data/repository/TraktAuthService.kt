package com.nuvio.tv.data.repository

import com.nuvio.tv.data.xmplayer.XMP_LINK_DEVICE_PREFIX as XMP_LINK_PREFIX
import com.nuvio.tv.data.xmplayer.XMP_BRIDGE_ERROR_HEADER
import com.nuvio.tv.data.xmplayer.XmpApiException
import com.nuvio.tv.data.xmplayer.XmpBridge
import com.nuvio.tv.data.xmplayer.XmpBridgeLinkPoll
import com.nuvio.tv.data.xmplayer.XmpBridgeProvider
import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TraktTokenPollResult {
    data object Pending : TraktTokenPollResult
    data object AlreadyUsed : TraktTokenPollResult
    data object Expired : TraktTokenPollResult
    data object Denied : TraktTokenPollResult
    data class SlowDown(val pollIntervalSeconds: Int) : TraktTokenPollResult
    data class Approved(val username: String?) : TraktTokenPollResult
    data class Failed(val reason: String) : TraktTokenPollResult
}

@Singleton
class TraktAuthService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApi,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    private val xmpBridge: XmpBridge
) {
    private val refreshLeewaySeconds = 60L
    private val writeRequestMutex = Mutex()
    private val tokenRefreshMutex = Mutex()
    private var lastWriteRequestAtMs = 0L
    private val minWriteIntervalMs = 1_000L
    private val transientRetryStatusCodes = setOf(502, 503, 504, 520, 521, 522)
    private val nonRetryableStatusCodes = setOf(400, 403, 404, 405, 409, 412, 420, 422, 423, 426)

    @Volatile private var circuitOpenUntilMs = 0L
    private val circuitFailures = AtomicInteger(0)
    private val circuitBaseCooldownMs = 5 * 60_000L
    private val circuitMaxCooldownMs = 60 * 60_000L

    private val rateLimitWindowMs = 5 * 60_000L
    private val rateLimitMaxCalls = 900
    private val getRequestTimestamps = ArrayDeque<Long>()
    private val rateLimitMutex = Mutex()

    private fun isCircuitOpen(): Boolean {
        return System.currentTimeMillis() < circuitOpenUntilMs
    }

    fun isCircuitClosed(): Boolean = !isCircuitOpen()

    private fun tripCircuit(reason: String) {
        val failures = circuitFailures.incrementAndGet()
        val cooldownMs = (circuitBaseCooldownMs shl (failures - 1).coerceAtMost(4))
            .coerceAtMost(circuitMaxCooldownMs)
        circuitOpenUntilMs = System.currentTimeMillis() + cooldownMs
        Log.w("TraktAuthService",
            "Circuit breaker OPEN: $reason (failures=$failures, cooldown=${cooldownMs / 1000}s)")
    }

    private fun resetCircuit() {
        if (circuitFailures.get() > 0) {
            trace("Circuit breaker reset after successful request")
        }
        circuitFailures.set(0)
        circuitOpenUntilMs = 0L
    }

    private suspend fun acquireGetRateSlot() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val windowStart = now - rateLimitWindowMs
            while (getRequestTimestamps.isNotEmpty() && getRequestTimestamps.first() < windowStart) {
                getRequestTimestamps.removeFirst()
            }
            if (getRequestTimestamps.size >= rateLimitMaxCalls) {
                val oldestInWindow = getRequestTimestamps.first()
                val waitMs = oldestInWindow + rateLimitWindowMs - now + 100L
                if (waitMs > 0) {
                    Log.w("TraktAuthService",
                        "GET rate limit approaching (${getRequestTimestamps.size}/$rateLimitMaxCalls in window), delaying ${waitMs}ms")
                    delay(waitMs)
                }
                val afterDelay = System.currentTimeMillis()
                val newWindowStart = afterDelay - rateLimitWindowMs
                while (getRequestTimestamps.isNotEmpty() && getRequestTimestamps.first() < newWindowStart) {
                    getRequestTimestamps.removeFirst()
                }
            }
            getRequestTimestamps.addLast(System.currentTimeMillis())
        }
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("TraktAuthService", message)
        }
    }

    private fun traktRedirectUri(): String {
        return BuildConfig.TRAKT_REDIRECT_URI.ifBlank { "urn:ietf:wg:oauth:2.0:oob" }
    }

    /** Trakt can be used here: this build's own Trakt app, or Trakt through the connected XMPlayer profile. */
    fun hasRequiredCredentials(): Boolean = hasDirectCredentials() || linksThroughXmp() || traktAuthViaXmpNow()

    /** This build carries its own Trakt app (id + secret) for signing in directly. */
    fun hasDirectCredentials(): Boolean {
        return BuildConfig.TRAKT_CLIENT_ID.isNotBlank() && BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()
    }

    /**
     * Connecting Trakt goes through XMPlayer: an XMPlayer profile is connected, has the Trakt bridge permission and a
     * plan that allows it. XMPlayer users link once (in XMPlayer or here) and need nothing else.
     */
    fun linksThroughXmp(): Boolean {
        val bridge = xmpBridge.state.value
        return bridge.connected && bridge.trakt.canLink
    }

    private fun traktAuthViaXmpNow(): Boolean = xmpBridge.activeNow(XmpBridgeProvider.TRAKT)

    /** Reads the connected XMPlayer profile's Trakt / Simkl link again (a link made in XMPlayer shows here at once). */
    suspend fun refreshXmpBridge() {
        xmpBridge.refresh()
    }

    /** Trakt runs through XMPlayer on a plan without scrobbling (Premium): XMPlayer refuses scrobbles and watched writes. */
    suspend fun xmpScrobbleBlocked(): Boolean {
        if (!getCurrentAuthState().viaXmp) return false
        return !xmpBridge.state.value.trakt.scrobbleAllowed
    }

    suspend fun getCurrentAuthState(): TraktAuthState = traktAuthDataStore.getCurrentState()

    suspend fun startDeviceAuth(): Result<TraktDeviceCodeResponseDto> {
        if (linksThroughXmp()) return startXmpLink()
        if (!hasDirectCredentials()) {
            return Result.failure(IllegalStateException(context.getString(R.string.trakt_error_missing_credentials)))
        }

        // Reuse an existing, still-valid device flow if one is already active.
        // This avoids re-hitting /oauth/device/code (which is tightly rate-limited
        // by Trakt) when the user navigates back to the login screen or taps the
        // Connect button twice in a row — see issue #1197.
        val state = getCurrentAuthState()
        val existingExpiresAt = state.expiresAt
        val existingCode = state.deviceCode
        if (
            !existingCode.isNullOrBlank() &&
            existingExpiresAt != null &&
            System.currentTimeMillis() < existingExpiresAt
        ) {
            return Result.success(
                TraktDeviceCodeResponseDto(
                    deviceCode = existingCode,
                    userCode = state.userCode.orEmpty(),
                    verificationUrl = state.verificationUrl.orEmpty(),
                    expiresIn = ((existingExpiresAt - System.currentTimeMillis()) / 1000L)
                        .coerceAtLeast(0L)
                        .toInt(),
                    interval = state.pollInterval ?: 5
                )
            )
        }

        // Issue a request, auto-retrying once on a transient 429 using the
        // Retry-After header when the server supplies a short back-off.
        suspend fun requestOnce(): Response<TraktDeviceCodeResponseDto> =
            traktApi.requestDeviceCode(
                TraktDeviceCodeRequestDto(clientId = BuildConfig.TRAKT_CLIENT_ID)
            )

        var response = try {
            requestOnce()
        } catch (e: IOException) {
            return Result.failure(IllegalStateException(context.getString(R.string.trakt_error_network_retry)))
        }

        if (response.code() == 429) {
            val retryAfterSeconds = response.headers()["Retry-After"]?.toLongOrNull()
            if (retryAfterSeconds != null && retryAfterSeconds in 1L..10L) {
                delay(retryAfterSeconds * 1000L)
                response = try {
                    requestOnce()
                } catch (e: IOException) {
                    return Result.failure(IllegalStateException(context.getString(R.string.trakt_error_network_retry)))
                }
            }
        }

        val body = response.body()
        if (response.isSuccessful && body != null) {
            traktAuthDataStore.saveDeviceFlow(body)
            return Result.success(body)
        }

        if (response.code() == 429) {
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            val minutes = ((retryAfter ?: 300L) + 59L) / 60L
            return Result.failure(
                IllegalStateException(context.getString(R.string.trakt_error_rate_limited_minutes, minutes))
            )
        }

        return Result.failure(
            IllegalStateException(context.getString(R.string.trakt_error_failed_start_code, response.code()))
        )
    }

    suspend fun pollDeviceToken(): TraktTokenPollResult {
        val state = getCurrentAuthState()
        val deviceCode = state.deviceCode
        if (deviceCode?.startsWith(XMP_LINK_PREFIX) == true) return pollXmpLink(deviceCode.removePrefix(XMP_LINK_PREFIX))

        if (!hasDirectCredentials()) {
            return TraktTokenPollResult.Failed(context.getString(R.string.trakt_error_missing_credentials))
        }
        if (deviceCode.isNullOrBlank()) {
            return TraktTokenPollResult.Failed(context.getString(R.string.trakt_error_no_active_device_code))
        }

        val response = try {
            traktApi.requestDeviceToken(
                TraktDeviceTokenRequestDto(
                    code = deviceCode,
                    clientId = BuildConfig.TRAKT_CLIENT_ID,
                    clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                )
            )
        } catch (e: IOException) {
            return TraktTokenPollResult.Failed(context.getString(R.string.trakt_error_network_will_retry))
        }

        val tokenBody = response.body()
        if (response.isSuccessful && tokenBody != null) {
            traktAuthDataStore.saveToken(tokenBody)
            authSessionNoticeDataStore.markTraktAuthenticated()
            resetCircuit()
            traktAuthDataStore.clearDeviceFlow()
            val user = fetchUserSettings()
            return TraktTokenPollResult.Approved(user)
        }

        return when (response.code()) {
            400 -> TraktTokenPollResult.Pending
            409 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.AlreadyUsed
            }
            404 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Failed(context.getString(R.string.trakt_error_invalid_device_code))
            }
            410 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Expired
            }
            418 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Denied
            }
            429 -> {
                val nextInterval = ((state.pollInterval ?: 5) + 5).coerceAtMost(60)
                traktAuthDataStore.updatePollInterval(nextInterval)
                TraktTokenPollResult.SlowDown(nextInterval)
            }
            else -> TraktTokenPollResult.Failed(
                context.getString(R.string.trakt_error_token_polling_failed_code, response.code())
            )
        }
    }

    /**
     * Links Trakt to the connected XMPlayer profile: XMPlayer answers a short code and an address (shown as the QR); the
     * user signs in to Trakt there. Kept in the same device-flow fields as a direct sign-in, so the screen is the same.
     */
    private suspend fun startXmpLink(): Result<TraktDeviceCodeResponseDto> {
        val state = getCurrentAuthState()
        val existing = state.deviceCode
        val existingExpiresAt = state.expiresAt
        if (existing?.startsWith(XMP_LINK_PREFIX) == true && existingExpiresAt != null && System.currentTimeMillis() < existingExpiresAt) {
            return Result.success(
                TraktDeviceCodeResponseDto(
                    deviceCode = existing,
                    userCode = state.userCode.orEmpty(),
                    verificationUrl = state.verificationUrl.orEmpty(),
                    expiresIn = ((existingExpiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt(),
                    interval = state.pollInterval ?: 5
                )
            )
        }
        val link = try {
            xmpBridge.startLink(XmpBridgeProvider.TRAKT)
        } catch (e: XmpApiException) {
            Log.w("TraktAuthService", "XMPlayer Trakt link failed to start: ${e.message}")
            return Result.failure(IllegalStateException(xmpErrorMessage(e)))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("TraktAuthService", "XMPlayer Trakt link failed to start: ${e.message}")
            return Result.failure(IllegalStateException(context.getString(R.string.trakt_error_network_retry)))
        }
        val flow = TraktDeviceCodeResponseDto(
            deviceCode = XMP_LINK_PREFIX + link.linkId,
            userCode = link.code,
            verificationUrl = link.url,
            expiresIn = ((link.expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L).toInt(),
            interval = link.intervalSeconds
        )
        traktAuthDataStore.saveDeviceFlow(flow)
        return Result.success(flow)
    }

    private suspend fun pollXmpLink(linkId: String): TraktTokenPollResult {
        val result = try {
            xmpBridge.pollLink(XmpBridgeProvider.TRAKT, linkId)
        } catch (e: XmpApiException) {
            if (e.status == 403 || e.status == 404) {
                traktAuthDataStore.clearDeviceFlow()
                return TraktTokenPollResult.Failed(xmpErrorMessage(e))
            }
            Log.w("TraktAuthService", "XMPlayer Trakt link poll failed: ${e.message}")
            return TraktTokenPollResult.Pending
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("TraktAuthService", "XMPlayer Trakt link poll failed: ${e.message}")
            return TraktTokenPollResult.Pending
        }
        return when (result) {
            XmpBridgeLinkPoll.Pending -> TraktTokenPollResult.Pending
            is XmpBridgeLinkPoll.Linked -> {
                traktAuthDataStore.clearDeviceFlow()
                authSessionNoticeDataStore.markTraktAuthenticated()
                resetCircuit()
                TraktTokenPollResult.Approved(result.account ?: getCurrentAuthState().username)
            }
            is XmpBridgeLinkPoll.Failed -> {
                traktAuthDataStore.clearDeviceFlow()
                if (result.reason == "access_denied") {
                    TraktTokenPollResult.Denied
                } else {
                    TraktTokenPollResult.Failed(context.getString(R.string.xmp_bridge_link_failed, result.reason ?: "-"))
                }
            }
            XmpBridgeLinkPoll.Expired -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Expired
            }
        }
    }

    private fun xmpErrorMessage(e: XmpApiException): String = when (e.code) {
        "premium_required" -> context.getString(R.string.xmp_bridge_premium_required)
        "scope_required", "insufficient_scope" -> context.getString(R.string.xmp_bridge_reconnect_required)
        else -> context.getString(R.string.xmp_bridge_link_failed, e.code ?: (e.status?.toString() ?: "-"))
    }

    suspend fun refreshTokenIfNeeded(force: Boolean = false): Boolean {
        // Through XMPlayer there is no Trakt token here to refresh: XMPlayer keeps the link's token current.
        if (getCurrentAuthState().viaXmp) return !force
        if (!hasDirectCredentials()) return false

        return tokenRefreshMutex.withLock {
            val state = getCurrentAuthState()
            val refreshToken = state.refreshToken ?: return@withLock false
            if (!force && !isTokenExpiredOrExpiring(state)) {
                trace("refreshTokenIfNeeded: token still valid, skip refresh")
                return@withLock true
            }

            trace("refreshTokenIfNeeded: refreshing token (force=$force)")
            val response = try {
                traktApi.refreshToken(
                    TraktRefreshTokenRequestDto(
                        refreshToken = refreshToken,
                        clientId = BuildConfig.TRAKT_CLIENT_ID,
                        clientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
                        redirectUri = traktRedirectUri()
                    )
                )
            } catch (e: IOException) {
                Log.w("TraktAuthService", "Network error while refreshing token", e)
                return@withLock false
            }

            val tokenBody = response.body()
            if (!response.isSuccessful || tokenBody == null) {
                trace("refreshTokenIfNeeded: failed code=${response.code()}")
                if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                    invalidateCredentials(response.code())
                }
                return@withLock false
            }

            traktAuthDataStore.saveToken(tokenBody)
            authSessionNoticeDataStore.markTraktAuthenticated()
            resetCircuit()
            trace("refreshTokenIfNeeded: success")
            true
        }
    }

    suspend fun revokeAndLogout() {
        val state = getCurrentAuthState()
        if (state.viaXmp) {
            // Stop using XMPlayer's Trakt link on this device's profile; the link itself stays in XMPlayer.
            xmpBridge.setOptedOut(XmpBridgeProvider.TRAKT, true)
            traktAuthDataStore.clearDeviceFlow()
            authSessionNoticeDataStore.markTraktExplicitLogout()
            return
        }
        if (hasDirectCredentials()) {
            state.accessToken?.let { accessToken ->
                runCatching {
                    traktApi.revokeToken(
                        TraktRevokeRequestDto(
                            token = accessToken,
                            clientId = BuildConfig.TRAKT_CLIENT_ID,
                            clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                        )
                    )
                }
            }
        }
        authSessionNoticeDataStore.markTraktExplicitLogout()
        traktAuthDataStore.clearAuth()
    }

    suspend fun fetchUserSettings(): String? {
        val response = executeAuthorizedRequest { authHeader ->
            traktApi.getUserSettings(authorization = authHeader)
        } ?: return null

        if (!response.isSuccessful) return null

        val username = response.body()?.user?.username
        val slug = response.body()?.user?.ids?.slug
        traktAuthDataStore.saveUser(username = username, userSlug = slug)
        return username
    }

    suspend fun <T> executeAuthorizedRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        if (isCircuitOpen()) {
            trace("authorized request: circuit breaker is OPEN, skipping request")
            return null
        }

        var token = getValidAccessToken() ?: return null
        var retriedAuth = false
        var retriedRateLimit = false
        var retriedTransient = false
        var retriedNetwork = false

        while (true) {
            acquireGetRateSlot()

            val response = try {
                call("Bearer $token")
            } catch (e: IOException) {
                if (!retriedNetwork) {
                    trace("authorized request: network error, retrying once")
                    delay(1_000L)
                    retriedNetwork = true
                    continue
                }
                Log.w("TraktAuthService", "Network error during authorized request", e)
                return null
            }

            val code = response.code()

            if (response.isSuccessful) {
                resetCircuit()
                return response
            }

            // XMPlayer's own refusal (plan, link gone, path) is not a broken Trakt key: it never trips the breaker.
            if (response.raw().header(XMP_BRIDGE_ERROR_HEADER) != null) {
                trace("authorized request: XMPlayer refused $code for ${responseTarget(response)}")
                return response
            }

            if (code == 403) {
                tripCircuit("403 Forbidden (invalid API key or unapproved app) for ${responseTarget(response)}")
                return response
            }

            if (code == 423) {
                tripCircuit("423 Locked User Account for ${responseTarget(response)}")
                return response
            }

            if (code in nonRetryableStatusCodes) {
                trace("authorized request: non-retryable $code for ${responseTarget(response)}")
                return response
            }

            if (code == 401 && !retriedAuth) {
                val refreshed = refreshTokenIfNeeded(force = true)
                if (refreshed) {
                    trace("authorized request: 401 for ${responseTarget(response)}, retrying after token refresh")
                    token = getCurrentAuthState().accessToken ?: return response
                    retriedAuth = true
                    continue
                }
                tripCircuit("401 Unauthorized and token refresh failed for ${responseTarget(response)}")
                return response
            }

            if (code == 429 && !retriedRateLimit) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 2L, maxSeconds = 60L)
                trace("authorized request: 429 for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedRateLimit = true
                continue
            }

            if (code in transientRetryStatusCodes && !retriedTransient) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 30L, maxSeconds = 30L)
                trace("authorized request: transient $code for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedTransient = true
                continue
            }

            return response
        }
    }

    suspend fun <T> executePublicRequest(
        call: suspend () -> Response<T>
    ): Response<T>? {
        if (isCircuitOpen()) {
            trace("public request: circuit breaker is OPEN, skipping request")
            return null
        }

        var retriedRateLimit = false
        var retriedTransient = false
        var retriedNetwork = false

        while (true) {
            acquireGetRateSlot()

            val response = try {
                call()
            } catch (e: IOException) {
                if (!retriedNetwork) {
                    trace("public request: network error, retrying once")
                    delay(1_000L)
                    retriedNetwork = true
                    continue
                }
                Log.w("TraktAuthService", "Network error during public request", e)
                return null
            }

            val code = response.code()

            if (response.isSuccessful) {
                resetCircuit()
                return response
            }

            if (response.raw().header(XMP_BRIDGE_ERROR_HEADER) != null) {
                trace("public request: XMPlayer refused $code for ${responseTarget(response)}")
                return response
            }

            if (code == 423) {
                tripCircuit("423 Locked User Account for ${responseTarget(response)}")
                return response
            }

            if (code == 401 || code == 403 || code in nonRetryableStatusCodes) {
                trace("public request: non-retryable $code for ${responseTarget(response)}")
                return response
            }

            if (code == 429 && !retriedRateLimit) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 2L, maxSeconds = 60L)
                trace("public request: 429 for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedRateLimit = true
                continue
            }

            if (code in transientRetryStatusCodes && !retriedTransient) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 30L, maxSeconds = 30L)
                trace("public request: transient $code for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedTransient = true
                continue
            }

            return response
        }
    }

    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        writeRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (lastWriteRequestAtMs + minWriteIntervalMs - now).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            lastWriteRequestAtMs = System.currentTimeMillis()
        }
        return executeAuthorizedRequest(call)
    }

    private suspend fun getValidAccessToken(): String? {
        val state = getCurrentAuthState()
        if (state.accessToken.isNullOrBlank()) return null
        if (refreshTokenIfNeeded(force = false)) {
            return getCurrentAuthState().accessToken
        }
        return null
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAt = createdAt + expiresIn
        val nowSeconds = System.currentTimeMillis() / 1000L
        return nowSeconds >= (expiresAt - refreshLeewaySeconds)
    }

    private suspend fun delayForRetryAfter(
        response: Response<*>,
        fallbackSeconds: Long,
        maxSeconds: Long
    ): Long {
        val retryAfterSeconds = response.headers()["Retry-After"]
            ?.toLongOrNull()
            ?.coerceIn(1L, maxSeconds)
            ?: fallbackSeconds
        delay(retryAfterSeconds * 1000L)
        return retryAfterSeconds
    }

    private fun responseTarget(response: Response<*>): String {
        val requestUrl = response.raw().request.url
        return buildString {
            append(requestUrl.encodedPath)
            requestUrl.encodedQuery?.let {
                append('?')
                append(it)
            }
        }
    }

    private suspend fun invalidateCredentials(statusCode: Int) {
        if (getCurrentAuthState().viaXmp) {
            xmpBridge.onLinkLost(XmpBridgeProvider.TRAKT)
            return
        }
        authSessionNoticeDataStore.markTraktReconnectRequired()
        traktAuthDataStore.clearAuth()
        tripCircuit("Token refresh returned $statusCode")
    }
}
