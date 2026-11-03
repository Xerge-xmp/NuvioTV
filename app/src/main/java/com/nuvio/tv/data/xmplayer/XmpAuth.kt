package com.nuvio.tv.data.xmplayer

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface XmpAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

@Singleton
class AndroidXmpAuthPersistence @Inject constructor(
    @ApplicationContext context: Context
) : XmpAuthPersistence {
    private val preferences = ProtectedProfilePreferences(context, "xmplayer_auth", "com.nuvio.tv.xmplayer.credentials.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}

/**
 * The connection of each Nuvio profile (a Nuvio profile connects to ONE XMPlayer profile — the one the user picks when
 * approving the code). The [XmpAuthScope] generation moves on every sign-in / sign-out / profile switch, so work started
 * for one connection can never land in another.
 */
class XmpAuthStore(
    private val persistence: XmpAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = XmpAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<XmpAuthState> = mutableState.asStateFlow()

    fun scope(): XmpAuthScope = synchronized(lock) { currentScope }
    fun isCurrent(scope: XmpAuthScope): Boolean = synchronized(lock) { currentScope == scope }
    fun checkScope(scope: XmpAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("XMPlayer account changed")
    }

    fun authorization(): XmpAuthorization? = synchronized(lock) { stored.tokens?.let { XmpAuthorization(currentScope, it) } }
    fun deviceCode(scope: XmpAuthScope): String? = synchronized(lock) { stored.deviceCode.takeIf { currentScope == scope } }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        stored = load(profileId)
        currentScope = XmpAuthScope(profileId, currentScope.generation + 1)
        publish()
    }

    fun saveSession(session: XmpDeviceSession, deviceCode: String, scope: XmpAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) { it.copy(session = session, deviceCode = deviceCode) }

    fun updateSession(session: XmpDeviceSession, scope: XmpAuthScope): Boolean = mutate(scope) { it.copy(session = session) }

    fun cancelSession(error: XmpAuthError? = null, scope: XmpAuthScope = scope()): Boolean =
        mutate(scope, advanceGeneration = true, error = error) { it.copy(session = null, deviceCode = null) }

    fun authorize(tokens: XmpTokens, scope: XmpAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) { XmpStoredAuth(tokens = tokens) }

    fun refreshTokens(tokens: XmpTokens, expected: XmpAuthorization): Boolean = synchronized(lock) {
        if (stored.tokens?.accessToken != expected.tokens.accessToken) return@synchronized false
        mutate(expected.scope) { it.copy(tokens = tokens) }
    }

    fun saveAccount(account: XmpAccount, scope: XmpAuthScope): Boolean = mutate(scope) { it.copy(account = account) }

    fun clearAuth(scope: XmpAuthScope = scope(), error: XmpAuthError? = null, expectedAccessToken: String? = null): Boolean =
        synchronized(lock) {
            if (expectedAccessToken != null && stored.tokens?.accessToken != expectedAccessToken) return@synchronized false
            mutate(scope, advanceGeneration = true, error = error) { XmpStoredAuth() }
        }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = XmpStoredAuth()
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = XmpStoredAuth()
        publish()
    }

    private fun mutate(
        scope: XmpAuthScope,
        advanceGeneration: Boolean = false,
        error: XmpAuthError? = null,
        transform: (XmpStoredAuth) -> XmpStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, json.encodeToString(value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): XmpStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { json.decodeFromString<XmpStoredAuth>(value) }.getOrNull()
    }?.takeIf { it.tokens == null || (it.tokens.accessToken.isNotBlank() && it.tokens.refreshToken.isNotBlank()) }
        ?: XmpStoredAuth()

    private fun publish(error: XmpAuthError? = null) {
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: XmpAuthError? = null) = XmpAuthState(
        scope = currentScope,
        isAuthenticated = stored.tokens != null,
        account = stored.account,
        session = stored.session,
        error = error
    )
}

/** The device-code sign-in (`/v1/oauth/device/code` → `/v1/oauth/token`), refresh, and revoke. */
class XmpAuthRepository(
    private val http: XmpHttpClient,
    private val configuration: XmpConfiguration,
    private val store: XmpAuthStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val deviceMutex = Mutex()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val state = store.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startDeviceAuthorization(scope: XmpAuthScope = store.scope()): XmpDeviceSession = deviceMutex.withLock {
        store.checkScope(scope)
        if (!hasRequiredCredentials()) throw XmpAuthException(XmpAuthError.MISSING_CLIENT_ID)
        check(!state.value.isAuthenticated)
        val response = oauth("/v1/oauth/device/code", mapOf("scope" to XMP_SCOPES), scope)
        if (response.status !in 200..299) throw XmpApiException(response.status, response.errorCode())
        val payload = decode<XmpDeviceResponse>(response.body)
        val deviceCode = payload.deviceCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = payload.userCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val uri = verificationUrl(payload.verificationUri)
        val complete = payload.verificationUriComplete?.let(::verificationUrl)
            ?: uri.newBuilder().addQueryParameter("code", userCode.replace("-", "")).build()
        val interval = payload.interval.takeIf { it in 1..3_600 } ?: invalidResponse()
        val now = nowEpochMs()
        val session = XmpDeviceSession(
            userCode = userCode,
            verificationUri = uri.toString(),
            verificationUriComplete = complete.toString(),
            expiresAtEpochMs = expiresAt(payload.expiresIn, now),
            intervalSeconds = interval,
            nextPollAtEpochMs = now + interval * 1_000L
        )
        if (!store.saveSession(session, deviceCode, scope)) scopeChanged()
        session
    }

    suspend fun pollDeviceAuthorization(scope: XmpAuthScope = store.scope()): XmpDevicePollResult = deviceMutex.withLock {
        store.checkScope(scope)
        val session = state.value.session ?: scopeChanged()
        val now = nowEpochMs()
        if (now >= session.expiresAtEpochMs) {
            store.cancelSession(XmpAuthError.CODE_EXPIRED, scope)
            return@withLock XmpDevicePollResult.Expired
        }
        if (now < session.nextPollAtEpochMs) return@withLock XmpDevicePollResult.Pending
        val deviceCode = store.deviceCode(scope) ?: scopeChanged()
        if (!store.updateSession(session.copy(nextPollAtEpochMs = now + session.intervalSeconds * 1_000L), scope)) scopeChanged()
        val response = oauth(
            "/v1/oauth/token",
            mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to deviceCode),
            scope
        )
        if (response.status in 200..299) {
            if (!store.authorize(tokensFrom(response), scope)) scopeChanged()
            return@withLock XmpDevicePollResult.Authorized
        }
        when (response.errorCode()) {
            "authorization_pending" -> XmpDevicePollResult.Pending
            "slow_down" -> {
                val interval = (session.intervalSeconds + 5).coerceAtMost(3_600)
                store.updateSession(session.copy(intervalSeconds = interval, nextPollAtEpochMs = nowEpochMs() + interval * 1_000L), scope)
                XmpDevicePollResult.Pending
            }
            "access_denied" -> {
                store.cancelSession(XmpAuthError.ACCESS_DENIED, scope)
                XmpDevicePollResult.Denied
            }
            "expired_token" -> {
                store.cancelSession(XmpAuthError.CODE_EXPIRED, scope)
                XmpDevicePollResult.Expired
            }
            else -> throw XmpApiException(response.status, response.errorCode())
        }
    }

    fun cancelDeviceAuthorization() {
        store.cancelSession()
    }

    /**
     * A usable access token: refreshed when it is within a minute of its day, or when the API rejected
     * [rejectedAccessToken]. Every refresh replaces the refresh token (the API rotates it).
     */
    suspend fun authorization(scope: XmpAuthScope, rejectedAccessToken: String? = null): XmpAuthorization = refreshMutex.withLock {
        store.checkScope(scope)
        val current = store.authorization() ?: throw XmpAuthException(XmpAuthError.AUTHORIZATION_REVOKED)
        if (rejectedAccessToken != null && current.tokens.accessToken != rejectedAccessToken) return@withLock current
        if (rejectedAccessToken == null && current.tokens.expiresAtEpochMs - nowEpochMs() > 60_000L) return@withLock current
        val response = oauth("/v1/oauth/token", mapOf("grant_type" to "refresh_token", "refresh_token" to current.tokens.refreshToken), scope)
        if (response.status !in 200..299) {
            if (response.errorCode() in setOf("invalid_grant", "invalid_token", "token_revoked")) {
                store.clearAuth(scope, XmpAuthError.AUTHORIZATION_REVOKED, current.tokens.accessToken)
                throw XmpAuthException(XmpAuthError.AUTHORIZATION_REVOKED)
            }
            throw XmpApiException(response.status, response.errorCode())
        }
        val tokens = tokensFrom(response, current.tokens.refreshToken)
        if (!store.refreshTokens(tokens, current)) scopeChanged()
        XmpAuthorization(scope, tokens)
    }

    /** Sign out here and tell XMPlayer (the connection leaves the user's Connected apps). */
    suspend fun disconnect(scope: XmpAuthScope = store.scope()): Boolean {
        store.checkScope(scope)
        val current = store.authorization()
        if (current != null && current.scope != scope) scopeChanged()
        if (!store.clearAuth(scope)) scopeChanged()
        if (current == null) return true
        return try {
            http.execute(
                XmpHttpRequest(
                    method = XmpHttpMethod.POST,
                    path = "/v1/oauth/revoke",
                    form = mapOf("client_id" to configuration.clientId, "token" to current.tokens.refreshToken)
                )
            ).status in 200..299
        } catch (error: CancellationException) {
            throw error
        } catch (_: XmpApiException) {
            false
        }
    }

    private suspend fun oauth(path: String, fields: Map<String, String>, scope: XmpAuthScope) = http.execute(
        XmpHttpRequest(
            method = XmpHttpMethod.POST,
            path = path,
            form = fields + ("client_id" to configuration.clientId),
            limitKey = "oauth:${scope.profileId}"
        ),
        checkScope = { store.checkScope(scope) }
    )

    private fun tokensFrom(response: XmpHttpResponse, previousRefreshToken: String? = null): XmpTokens {
        val payload = decode<XmpTokenResponse>(response.body)
        val accessToken = payload.accessToken?.takeIf { it.startsWith("xmp_") } ?: invalidResponse()
        val refreshToken = payload.refreshToken?.takeIf(String::isNotBlank) ?: previousRefreshToken ?: invalidResponse()
        if (!payload.tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        // A token without history:read cannot do the job Nuvio connects it for.
        if (payload.scope != null && "history:read" !in payload.scope.split(Regex("\\s+"))) {
            throw XmpAuthException(XmpAuthError.INSUFFICIENT_SCOPE)
        }
        return XmpTokens(accessToken, refreshToken, expiresAt(payload.expiresIn, nowEpochMs()))
    }

    private fun verificationUrl(value: String?) = value?.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && (it.host == "xmplayer.app" || it.host.endsWith(".xmplayer.app")) && it.username.isEmpty() && it.password.isEmpty() }
        ?: invalidResponse()

    private fun expiresAt(seconds: Long?, now: Long): Long = seconds
        ?.takeIf { it > 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L } ?: invalidResponse()

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }.getOrElse { invalidResponse() }

    private fun invalidResponse(): Nothing = throw XmpAuthException(XmpAuthError.INVALID_RESPONSE)
    private fun scopeChanged(): Nothing = throw CancellationException("XMPlayer account changed")
}
