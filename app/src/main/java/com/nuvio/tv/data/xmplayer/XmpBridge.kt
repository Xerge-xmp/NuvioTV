package com.nuvio.tv.data.xmplayer

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Trakt and Simkl through XMPlayer ("the bridge"). The connected XMPlayer profile's own Trakt / Simkl link is used:
 * XMPlayer's server holds the Trakt / Simkl app secrets and the user's provider tokens, adds them, and passes Nuvio's
 * calls through (`/v1/trakt/…`, `/v1/simkl/…`). Nuvio holds no provider key or token for a bridged profile — only its
 * XMPlayer connection. Linking from Nuvio stores the same per-profile link XMPlayer uses, so linked in one = linked in both.
 *
 * Plan rules are XMPlayer's: the bridge needs Premium; scrobbling and watched-history writes need Pro.
 */
enum class XmpBridgeProvider(val wire: String, val scope: String) {
    TRAKT("trakt", "trakt:bridge"),
    SIMKL("simkl", "simkl:bridge")
}

/** Stands in for a provider token on a bridged profile; the bridge interceptor swaps in the XMPlayer access token. */
const val XMP_BRIDGE_TOKEN = "xmplayer-bridge"

/** A Trakt device-flow code that is really an XMPlayer link id (Trakt being linked through XMPlayer). */
const val XMP_LINK_DEVICE_PREFIX = "xmplink:"

/** The header XMPlayer puts on its own refusals (as opposed to the provider's answers, which pass through as they are). */
const val XMP_BRIDGE_ERROR_HEADER = "X-Bridge-Error"

/** One provider as the connected XMPlayer profile has it. */
data class XmpBridgeStatus(
    /** This XMPlayer connection holds the provider's bridge permission. */
    val granted: Boolean = false,
    /** XMPlayer's server has the provider's app set up. */
    val configured: Boolean = false,
    val linked: Boolean = false,
    val account: String? = null,
    /** The profile's plan allows the bridge (Premium and Pro). */
    val allowed: Boolean = false,
    /** The plan allows scrobbling and watched-history writes (Pro). */
    val scrobbleAllowed: Boolean = false,
    /** The user chose, on this device, not to use XMPlayer's link for this provider. */
    val optedOut: Boolean = false
) {
    val active: Boolean get() = granted && configured && linked && allowed && !optedOut

    /** Linking through XMPlayer is possible from here (not linked yet, or linking again). */
    val canLink: Boolean get() = granted && configured && allowed
}

data class XmpBridgeState(
    val connected: Boolean = false,
    val trakt: XmpBridgeStatus = XmpBridgeStatus(),
    val simkl: XmpBridgeStatus = XmpBridgeStatus()
) {
    fun of(provider: XmpBridgeProvider): XmpBridgeStatus = when (provider) {
        XmpBridgeProvider.TRAKT -> trakt
        XmpBridgeProvider.SIMKL -> simkl
    }

    fun active(provider: XmpBridgeProvider): Boolean = connected && of(provider).active
}

data class XmpBridgeLinkSession(
    val linkId: String,
    val code: String,
    val url: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int
)

sealed interface XmpBridgeLinkPoll {
    data object Pending : XmpBridgeLinkPoll
    data class Linked(val account: String?) : XmpBridgeLinkPoll
    data class Failed(val reason: String?) : XmpBridgeLinkPoll
    data object Expired : XmpBridgeLinkPoll
}

@Serializable
internal data class XmpIntegrationDto(
    val scope: Boolean = false,
    val configured: Boolean = false,
    val linked: Boolean = false,
    val account: String? = null,
    val linkedAt: Long? = null,
    val allowed: Boolean = false,
    val scrobbleAllowed: Boolean = false
)

@Serializable
internal data class XmpIntegrationsResponse(
    val profileId: String? = null,
    val tier: String? = null,
    val trakt: XmpIntegrationDto? = null,
    val simkl: XmpIntegrationDto? = null
)

@Serializable
internal data class XmpBridgeLinkResponse(
    val linkId: String,
    val code: String,
    val url: String,
    val expiresIn: Long = 900,
    val interval: Int = 5
)

@Serializable
internal data class XmpBridgeLinkPollResponse(
    val status: String,
    val account: String? = null,
    val reason: String? = null
)

/** GET /v1/integrations, and the link flow. None of these count toward the Sync API's reads a minute. */
class XmpBridgeClient(private val api: XmpApiClient) {
    internal suspend fun status(): XmpIntegrationsResponse =
        api.decode(api.get("/v1/integrations").body)

    suspend fun startLink(provider: XmpBridgeProvider): XmpBridgeLinkSession {
        val link = api.decode<XmpBridgeLinkResponse>(api.post("/v1/integrations/${provider.wire}/link", "{}").body)
        return XmpBridgeLinkSession(
            linkId = link.linkId,
            code = link.code,
            url = link.url,
            expiresAtEpochMs = System.currentTimeMillis() + link.expiresIn.coerceAtLeast(1L) * 1_000L,
            intervalSeconds = link.interval.coerceIn(1, 60)
        )
    }

    suspend fun pollLink(provider: XmpBridgeProvider, linkId: String): XmpBridgeLinkPoll {
        val poll = api.decode<XmpBridgeLinkPollResponse>(
            api.get("/v1/integrations/${provider.wire}/link/${java.net.URLEncoder.encode(linkId, "UTF-8")}").body
        )
        return when (poll.status) {
            "linked" -> XmpBridgeLinkPoll.Linked(poll.account)
            "failed" -> XmpBridgeLinkPoll.Failed(poll.reason)
            "expired" -> XmpBridgeLinkPoll.Expired
            else -> XmpBridgeLinkPoll.Pending
        }
    }
}

/**
 * The bridge state per Nuvio profile, kept on the device so a cold start knows at once whether Trakt / Simkl are bridged
 * (before /v1/integrations has answered). Refreshed when the XMPlayer connection or the profile changes, when the Trakt /
 * Simkl settings open, after a link, and when XMPlayer says a link is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XmpBridge(
    private val factory: ProfileDataStoreFactory,
    activeProfileId: StateFlow<Int>,
    private val authStore: XmpAuthStore,
    private val auth: XmpAuthRepository,
    private val client: XmpBridgeClient,
    private val configuration: XmpConfiguration,
    private val scope: CoroutineScope
) {
    private val connected: Flow<Pair<Int, Boolean>> = authStore.state
        .map { it.scope.profileId to it.isAuthenticated }
        .distinctUntilChanged()

    /** The active profile's bridge. */
    val state: StateFlow<XmpBridgeState> = activeProfileId
        .flatMapLatest { profileId ->
            combine(stored(profileId), connected) { stored, (authProfile, authenticated) ->
                stored.copy(connected = authenticated && authProfile == profileId)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, XmpBridgeState())

    init {
        scope.launch {
            connected.collect { (_, authenticated) -> if (authenticated) refresh() }
        }
    }

    /** True when calls for [provider] on the active profile go through XMPlayer right now. */
    fun activeNow(provider: XmpBridgeProvider): Boolean = state.value.active(provider)

    /** The bridge as [profileId] has it (connected only when that profile's XMPlayer connection is the live one). */
    suspend fun stateFor(profileId: Int): XmpBridgeState {
        val auth = authStore.state.value
        return stored(profileId).first().copy(connected = auth.isAuthenticated && auth.scope.profileId == profileId)
    }

    /** Reads /v1/integrations for the connected profile and keeps it. A connection without the bridge permissions reads as not granted. */
    suspend fun refresh(): XmpBridgeState {
        val authScope = authStore.scope()
        if (!authStore.state.value.isAuthenticated) return state.value
        val response = try {
            client.status()
        } catch (e: XmpApiException) {
            if (e.status == 403 && (e.code == "scope_required" || e.code == "insufficient_scope")) {
                save(authScope.profileId, XmpBridgeProvider.TRAKT, XmpIntegrationDto())
                save(authScope.profileId, XmpBridgeProvider.SIMKL, XmpIntegrationDto())
            } else {
                Log.w(TAG, "integrations status failed: ${e.message}")
            }
            return state.value
        } catch (e: XmpAuthException) {
            Log.w(TAG, "integrations status: connection ended (${e.error})")
            return state.value
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "integrations status failed: ${e.message}")
            return state.value
        }
        if (!authStore.isCurrent(authScope)) return state.value
        save(authScope.profileId, XmpBridgeProvider.TRAKT, response.trakt ?: XmpIntegrationDto())
        save(authScope.profileId, XmpBridgeProvider.SIMKL, response.simkl ?: XmpIntegrationDto())
        Log.i(
            TAG,
            "integrations tier=${response.tier} trakt(scope=${response.trakt?.scope} linked=${response.trakt?.linked} allowed=${response.trakt?.allowed}) " +
                "simkl(scope=${response.simkl?.scope} linked=${response.simkl?.linked} allowed=${response.simkl?.allowed})"
        )
        return stateFor(authScope.profileId)
    }

    suspend fun startLink(provider: XmpBridgeProvider): XmpBridgeLinkSession = client.startLink(provider)

    suspend fun pollLink(provider: XmpBridgeProvider, linkId: String): XmpBridgeLinkPoll {
        val result = client.pollLink(provider, linkId)
        if (result is XmpBridgeLinkPoll.Linked) {
            setOptedOut(provider, false)
            refresh()
        }
        return result
    }

    /** Use (or stop using) XMPlayer's link for [provider] on this device's active profile. XMPlayer's link itself is untouched. */
    suspend fun setOptedOut(provider: XmpBridgeProvider, optedOut: Boolean) {
        val profileId = authStore.scope().profileId
        factory.get(profileId, FEATURE).edit { it[key(provider, "opt_out")] = optedOut }
    }

    fun setOptedOutAsync(provider: XmpBridgeProvider, optedOut: Boolean) {
        scope.launch { setOptedOut(provider, optedOut) }
    }

    /** XMPlayer said the link is gone (not_linked / link_expired): show it as not linked and read the status again. */
    fun onLinkLost(provider: XmpBridgeProvider) {
        val profileId = authStore.scope().profileId
        scope.launch {
            factory.get(profileId, FEATURE).edit { it[key(provider, "linked")] = false }
            refresh()
        }
    }

    /** The XMPlayer access token for a passthrough call; [rejected] = the one XMPlayer just refused (refresh it). */
    suspend fun accessToken(rejected: String? = null): String =
        auth.authorization(authStore.scope(), rejected).tokens.accessToken

    /** https://stateroom.xmplayer.app/v1/<provider>/ — where a bridged call goes instead of the provider's own API. */
    fun passthroughBase(provider: XmpBridgeProvider): String =
        "${configuration.baseUrl.trimEnd('/')}/v1/${provider.wire}/"

    private fun stored(profileId: Int): Flow<XmpBridgeState> = factory.get(profileId, FEATURE).data.map { prefs ->
        fun status(provider: XmpBridgeProvider) = XmpBridgeStatus(
            granted = prefs[key(provider, "scope")] ?: false,
            configured = prefs[key(provider, "configured")] ?: false,
            linked = prefs[key(provider, "linked")] ?: false,
            account = prefs[stringPreferencesKey("${provider.wire}_account")],
            allowed = prefs[key(provider, "allowed")] ?: false,
            scrobbleAllowed = prefs[key(provider, "scrobble")] ?: false,
            optedOut = prefs[key(provider, "opt_out")] ?: false
        )
        XmpBridgeState(trakt = status(XmpBridgeProvider.TRAKT), simkl = status(XmpBridgeProvider.SIMKL))
    }

    private suspend fun save(profileId: Int, provider: XmpBridgeProvider, dto: XmpIntegrationDto) {
        factory.get(profileId, FEATURE).edit { prefs ->
            prefs[key(provider, "scope")] = dto.scope
            prefs[key(provider, "configured")] = dto.configured
            prefs[key(provider, "linked")] = dto.linked
            prefs[key(provider, "allowed")] = dto.allowed
            prefs[key(provider, "scrobble")] = dto.scrobbleAllowed
            val accountKey = stringPreferencesKey("${provider.wire}_account")
            val account = dto.account?.takeIf(String::isNotBlank)
            if (account == null) prefs.remove(accountKey) else prefs[accountKey] = account
        }
    }

    private fun key(provider: XmpBridgeProvider, name: String) = booleanPreferencesKey("${provider.wire}_$name")

    private companion object {
        const val FEATURE = "xmp_bridge"
        const val TAG = "XmpBridge"
    }
}
