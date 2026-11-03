package com.nuvio.tv.data.simkl

import com.nuvio.tv.data.xmplayer.XmpApiException
import com.nuvio.tv.data.xmplayer.XmpBridge
import com.nuvio.tv.data.xmplayer.XmpBridgeLinkPoll
import com.nuvio.tv.data.xmplayer.XmpBridgeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklAuthRepository(
    private val apiClient: SimklApiClient,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val bridge: XmpBridge? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        apiClient: SimklApiClient,
        configuration: SimklApiConfiguration,
        storage: SimklAuthStorage,
        bridge: XmpBridge
    ) : this(apiClient, configuration, storage, bridge, System::currentTimeMillis)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val state: StateFlow<SimklAuthState> = storage.state

    /** Simkl can be used here: this build's own Simkl app id, or Simkl through the connected XMPlayer profile. */
    fun hasRequiredCredentials(): Boolean =
        configuration.clientId.isNotBlank() || linksThroughXmp() || bridge?.activeNow(XmpBridgeProvider.SIMKL) == true

    /** Connecting Simkl goes through XMPlayer: an XMPlayer profile is connected, with the Simkl bridge permission and a plan for it. */
    fun linksThroughXmp(): Boolean = bridge?.state?.value?.let { it.connected && it.simkl.canLink } == true

    /** Reads the connected XMPlayer profile's Simkl link again (a link made in XMPlayer shows here at once). */
    suspend fun refreshXmpBridge() {
        bridge?.refresh()
    }

    /** Simkl runs through XMPlayer on a plan without scrobbling (Premium): XMPlayer refuses scrobbles and watched writes. */
    fun xmpScrobbleBlocked(): Boolean =
        storage.state.value.viaXmp && bridge?.state?.value?.simkl?.scrobbleAllowed != true

    suspend fun startPinAuth(): SimklPinSession = mutex.withLock {
        val authScope = storage.currentScope()
        val xmp = bridge
        if (xmp != null && linksThroughXmp()) {
            val link = xmp.startLink(XmpBridgeProvider.SIMKL)
            val session = SimklPinSession(
                userCode = link.code,
                verificationUri = link.url,
                expiresAtEpochMs = link.expiresAtEpochMs,
                intervalSeconds = link.intervalSeconds,
                xmpLinkId = link.linkId
            )
            if (!storage.savePinSession(session, authScope)) throw SimklAuthException(SimklAuthError.PIN_INVALIDATED)
            return@withLock session
        }
        if (configuration.clientId.isBlank()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        val response = executeAuthRequest(SimklApiRequest(SimklHttpMethod.GET, "/oauth/pin", requiresAuthentication = false))
        val payload = decodePinResponse(response.body)
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = (payload.verificationUri ?: payload.verificationUrl)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        val interval = payload.interval?.coerceAtLeast(1)
        if (payload.result != "OK" || userCode == null || verificationUri == null || expiresIn == null || interval == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        val session = SimklPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtEpochMs = nowEpochMs() + expiresIn * 1_000L,
            intervalSeconds = interval
        )
        if (!storage.savePinSession(session, authScope)) {
            throw SimklAuthException(SimklAuthError.PIN_INVALIDATED)
        }
        session
    }

    suspend fun pollPin(): SimklPinPollResult = mutex.withLock {
        val authScope = storage.currentScope()
        val session = storage.state.value.pinSession ?: return@withLock SimklPinPollResult.Invalidated
        if (nowEpochMs() >= session.expiresAtEpochMs) {
            storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
            return@withLock SimklPinPollResult.Expired
        }
        val linkId = session.xmpLinkId
        val xmp = bridge
        if (linkId != null && xmp != null) return@withLock pollXmpLink(xmp, linkId, authScope)
        if (!PIN_PATTERN.matches(session.userCode)) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/oauth/pin/${session.userCode}",
                requiresAuthentication = false
            )
        )
        val payload = decodePinResponse(response.body)
        if (!payload.deviceCode.isNullOrBlank()) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val token = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
        if (payload.result == "OK" && token != null) {
            if (!storage.completePinAuthorization(token, authScope)) {
                return@withLock SimklPinPollResult.Invalidated
            }
            try {
                refreshUserSettings(scope = authScope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
            return@withLock SimklPinPollResult.Authorized
        }
        if (payload.result == "KO") return@withLock SimklPinPollResult.Pending
        throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    }

    suspend fun refreshUserSettings(
        activityWatermark: String? = null,
        scope: SimklAuthScope = storage.currentScope()
    ): String? {
        if (!storage.isCurrent(scope) || !storage.state.value.isAuthenticated) return null
        val response = apiClient.execute(
            request = SimklApiRequest(SimklHttpMethod.POST, "/users/settings"),
            expectedAuthScope = scope
        )
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }.getOrNull()
            ?: return null
        val username = settings.user?.name?.trim()?.takeIf(String::isNotBlank)
        val saved = storage.saveIdentity(
            username = username,
            accountId = settings.account?.id,
            settingsActivityWatermark = activityWatermark,
            scope = scope
        )
        return username.takeIf { saved }
    }

    suspend fun synchronizeUserSettings(activityWatermark: String?) {
        val authScope = storage.currentScope()
        if (!storage.state.value.isAuthenticated) return
        try {
            when (simklSettingsRefreshAction(storage.state.value, activityWatermark)) {
                SimklSettingsRefreshAction.NONE -> Unit
                SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                    storage.recordSettingsActivityWatermark(requireNotNull(activityWatermark), authScope)
                }
                SimklSettingsRefreshAction.FETCH -> refreshUserSettings(activityWatermark, authScope)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    private suspend fun pollXmpLink(xmp: XmpBridge, linkId: String, authScope: SimklAuthScope): SimklPinPollResult {
        val result = try {
            xmp.pollLink(XmpBridgeProvider.SIMKL, linkId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: XmpApiException) {
            if (error.status == 403 || error.status == 404) {
                storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
                return SimklPinPollResult.Invalidated
            }
            return SimklPinPollResult.Pending
        } catch (_: Throwable) {
            return SimklPinPollResult.Pending
        }
        return when (result) {
            XmpBridgeLinkPoll.Pending -> SimklPinPollResult.Pending
            is XmpBridgeLinkPoll.Linked -> {
                storage.clearPinSession(null, authScope)
                try {
                    refreshUserSettings(scope = authScope)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    Unit
                }
                SimklPinPollResult.Authorized
            }
            is XmpBridgeLinkPoll.Failed -> {
                storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
                SimklPinPollResult.Invalidated
            }
            XmpBridgeLinkPoll.Expired -> {
                storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
                SimklPinPollResult.Expired
            }
        }
    }

    fun cancelPinAuth() = storage.clearPinSession()

    fun disconnect() = storage.clearAuth()

    private suspend fun executeAuthRequest(request: SimklApiRequest): SimklApiResponse = try {
        apiClient.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimklAuthException) {
        throw error
    } catch (error: Throwable) {
        throw SimklAuthException(SimklAuthError.NETWORK, error)
    }

    private fun decodePinResponse(body: String): SimklPinResponse =
        runCatching { json.decodeFromString<SimklPinResponse>(body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }

    private companion object {
        val PIN_PATTERN = Regex("[A-Za-z0-9]{4,12}")
    }
}
