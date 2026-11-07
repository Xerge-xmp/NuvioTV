package com.nuvio.tv.data.xmplayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * XMPlayer Sync API (https://xmplayer.app/developers.html) — the user's XMPlayer favourites, watch history, lists and
 * ratings, two-way, plus scrobbling. A user connects with the TV device code (OAuth 2 device grant, public client).
 */
data class XmpConfiguration(
    val clientId: String,
    val appVersion: String,
    val baseUrl: String = "https://stateroom.xmplayer.app"
)

/**
 * The permissions Nuvio asks for. Writes need XMPlayer Premium or Pro; on Free the connection reads only. dvr:read plays
 * the profile's Cloud DVR recordings; catalog:read / catalog:write air and edit its IPTV catalog (Premium+, answered by
 * the account's XMPlayer DVR agent); trakt:bridge / simkl:bridge use the profile's XMPlayer Trakt / Simkl link (Premium+,
 * scrobbling Pro — see XmpBridge). A connection made before these existed keeps its old permissions until it connects again.
 */
internal const val XMP_SCOPES =
    "profile:read favorites:read favorites:write history:read history:write collections:read collections:write ratings:read ratings:write " +
        "dvr:read catalog:read catalog:write trakt:bridge simkl:bridge"

enum class XmpAuthError {
    MISSING_CLIENT_ID,
    INVALID_RESPONSE,
    INSUFFICIENT_SCOPE,
    CODE_EXPIRED,
    ACCESS_DENIED,
    ACCOUNT_FULL,
    AUTHORIZATION_REVOKED
}

data class XmpAuthScope(val profileId: Int, val generation: Long)

@Serializable
data class XmpDeviceSession(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val nextPollAtEpochMs: Long
) {
    override fun toString(): String = "XmpDeviceSession(expiresAtEpochMs=$expiresAtEpochMs)"
}

/** Who the connection is: the XMPlayer profile it works in, the plan, and what the plan allows (GET /v1/me). */
@Serializable
data class XmpAccount(
    val profileId: String,
    val profileName: String? = null,
    val tier: String = "free",
    val write: Boolean = false,
    val stream: Boolean = false,
    val readsPerMinute: Int = 1
) {
    val tierLabel: String
        get() = tier.replaceFirstChar { it.uppercaseChar() }
}

data class XmpAuthState(
    val scope: XmpAuthScope = XmpAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val account: XmpAccount? = null,
    val session: XmpDeviceSession? = null,
    val error: XmpAuthError? = null
)

@Serializable
data class XmpTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long
) {
    override fun toString(): String = "XmpTokens(expiresAtEpochMs=$expiresAtEpochMs)"
}

class XmpAuthorization(val scope: XmpAuthScope, val tokens: XmpTokens) {
    override fun toString(): String = "XmpAuthorization(scope=$scope)"
}

sealed interface XmpDevicePollResult {
    data object Pending : XmpDevicePollResult
    data object Authorized : XmpDevicePollResult
    data object Expired : XmpDevicePollResult
    data object Denied : XmpDevicePollResult
}

class XmpAuthException(val error: XmpAuthError) : Exception(error.name)

/** A body the client could not read. */
class XmpDecodingException : Exception("XMPlayer response could not be read")

@Serializable
internal data class XmpStoredAuth(
    val tokens: XmpTokens? = null,
    val account: XmpAccount? = null,
    val session: XmpDeviceSession? = null,
    val deviceCode: String? = null
) {
    override fun toString(): String = "XmpStoredAuth()"
}

// ── OAuth bodies ─────────────────────────────────────────────────────────────────────────────────────────

@Serializable
internal data class XmpDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int = 5
)

@Serializable
internal data class XmpTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("profile_id") val profileId: String? = null
)

// ── HTTP ─────────────────────────────────────────────────────────────────────────────────────────────────

enum class XmpHttpMethod { GET, POST }

/**
 * Which way a request travels. [API]: answered by the Sync API itself and counted in the plan's reads / writes a minute,
 * one at a time. [AGENT]: the catalog (`/v1/catalog…`), which the Sync API hands to the account's XMPlayer DVR agent and
 * does not count — several run at once, and an agent that is away answers every request waiting with it once.
 */
enum class XmpLane { API, AGENT }

class XmpHttpRequest(
    val method: XmpHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    val form: Map<String, String>? = null,
    val accessToken: String? = null,
    val limitKey: String = "public",
    val retrySafe: Boolean = method == XmpHttpMethod.GET,
    val lane: XmpLane = XmpLane.API
) {
    override fun toString(): String = "XmpHttpRequest(method=$method)"
}

class XmpHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** The API's error code: `{"error":"token_expired",…}` (OAuth answers use the same field). */
    fun errorCode(): String? = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }

    /** `retryAfter` (seconds) in a 429 body. */
    fun retryAfterSeconds(): Long? = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("retryAfter")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }.getOrNull()

    override fun toString(): String = "XmpHttpResponse(status=$status)"
}

class XmpApiException(
    val status: Int? = null,
    val code: String? = null,
    val retryAtEpochMs: Long? = null
) : Exception("XMPlayer request failed${status?.let { " (HTTP $it)" }.orEmpty()}${code?.let { " $it" }.orEmpty()}")

fun interface XmpHttpEngine {
    suspend fun execute(request: XmpHttpRequest): XmpHttpResponse
}

// ── API bodies (only the fields Nuvio uses; unknown fields are ignored) ─────────────────────────────────────

@Serializable
internal data class XmpIds(
    val tmdb: String? = null,
    val imdb: String? = null,
    val tvdb: String? = null,
    val mal: String? = null,
    val anilist: String? = null
)

/** An item as the API returns it: movie, series, episode (series ids + season + episode) or live. */
@Serializable
internal data class XmpItem(
    val type: String? = null,
    val tmdb: String? = null,
    val imdb: String? = null,
    val tvdb: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val seriesTitle: String? = null,
    val year: String? = null,
    val ids: XmpIds? = null
)

@Serializable
internal data class XmpMeResponse(
    val account: XmpMeAccount? = null,
    val profile: XmpMeProfile? = null,
    val limits: XmpMeLimits? = null,
    val cursor: Long? = null
)

@Serializable internal data class XmpMeAccount(val tier: String? = null)
@Serializable internal data class XmpMeProfile(val id: String? = null, val name: String? = null)
@Serializable internal data class XmpMeLimits(
    val readsPerMinute: Int? = null,
    val write: Boolean? = null,
    val stream: Boolean? = null
)

@Serializable
internal data class XmpHistoryResponse(
    val cursor: Long? = null,
    val items: List<XmpHistoryRow> = emptyList()
)

@Serializable
internal data class XmpHistoryRow(
    val key: String,
    val item: XmpItem? = null,
    val progress: Double? = null,
    val completed: Boolean? = null,
    val lastWatchedAt: Long? = null
)

@Serializable
internal data class XmpFavoritesResponse(
    val cursor: Long? = null,
    val items: List<XmpListRow> = emptyList()
)

@Serializable
internal data class XmpListRow(
    val key: String,
    val item: XmpItem? = null,
    val addedAt: Long? = null,
    val poster: String? = null
)

@Serializable
internal data class XmpCollectionsResponse(
    val cursor: Long? = null,
    val collections: List<XmpCollectionDto> = emptyList()
)

@Serializable
internal data class XmpCollectionDto(
    val id: String,
    val kind: String? = null,
    val name: String? = null,
    val type: String? = null,
    val items: List<XmpListRow> = emptyList()
)

@Serializable
internal data class XmpChangesResponse(
    val cursor: Long? = null,
    val more: Boolean = false,
    val resync: Boolean = false,
    val changes: List<XmpChange> = emptyList()
)

/**
 * One change from /v1/changes or /v1/stream. history: [changes] carries the fields that changed (progress,
 * lastWatchedAt, …) or [removed]; favorite: [favorite]; lists: [collection] + a member ([key] / [item] / [member]) or
 * the list itself ([name] / [type] / [deleted]).
 */
@Serializable
internal data class XmpChange(
    val cursor: Long? = null,
    val at: Long? = null,
    val kind: String? = null,
    val key: String? = null,
    val item: XmpItem? = null,
    val changes: XmpHistoryFields? = null,
    val removed: Boolean? = null,
    val favorite: Boolean? = null,
    val collection: String? = null,
    val member: Boolean? = null,
    val name: String? = null,
    val type: String? = null,
    val deleted: Boolean? = null
)

@Serializable
internal data class XmpHistoryFields(
    val progress: Double? = null,
    val completed: Boolean? = null,
    val lastWatchedAt: Long? = null
)

@Serializable
internal data class XmpEventsResponse(val results: List<XmpEventResult> = emptyList())

@Serializable
internal data class XmpEventResult(
    val index: Int? = null,
    val ok: Boolean = false,
    val key: String? = null,
    val error: String? = null,
    val collection: String? = null
)
