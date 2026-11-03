package com.nuvio.tv.data.xmplayer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The connected XMPlayer profile's own IPTV catalog and Cloud DVR, through the Sync API:
 * - /v1/catalog: the profile's playlists → movie / series categories (as XMPlayer's Source Editor shows them) → titles.
 *   Answered by the account's XMPlayer DVR agent; an edit is applied there with XMPlayer's own Source Editor, so every
 *   XMPlayer device follows. Names only: never a playlist address, login or stream link.
 * - /v1/dvr: the profile's finished recordings, and a short-lived HLS link to play one.
 */
enum class XmpCatalogKind(val wire: String) { MOVIES("movies"), SERIES("series") }

@Serializable
internal data class XmpCatalogSource(val id: String, val name: String = "")

@Serializable
internal data class XmpCatalogSourcesResponse(val sources: List<XmpCatalogSource> = emptyList())

@Serializable
data class XmpCatalogCategory(
    val id: String,
    val name: String = "",
    val providerName: String? = null,
    val count: Int? = null,
    /** How many of its titles XMPlayer has identified (TMDB / IMDb): the ones Nuvio can air. */
    val matched: Int? = null,
    val hidden: Boolean = false,
    val renamed: Boolean = false,
    val favorite: Boolean = false,
    val custom: Boolean = false
)

@Serializable
internal data class XmpCatalogCategoriesResponse(val categories: List<XmpCatalogCategory> = emptyList())

@Serializable
data class XmpCatalogTitle(
    val id: String,
    val name: String = "",
    val providerName: String? = null,
    val year: String? = null,
    val hidden: Boolean = false,
    val renamed: Boolean = false,
    /** "id" when XMPlayer knows the title (its TMDB / IMDb ids), "name" when it could not be matched. */
    val identity: String? = null,
    val key: String? = null,
    val ids: XmpCatalogIds? = null,
    val poster: String? = null
) {
    val tmdb: Long? get() = ids?.tmdb?.toLongOrNull()
    val imdb: String? get() = ids?.imdb?.takeIf { it.startsWith("tt") }
    val isMatched: Boolean get() = tmdb != null || imdb != null
}

@Serializable
data class XmpCatalogIds(val tmdb: String? = null, val imdb: String? = null)

@Serializable
internal data class XmpCatalogTitlesResponse(val titles: List<XmpCatalogTitle> = emptyList())

@Serializable
internal data class XmpCatalogEditResponse(val ok: Boolean = false, val did: String? = null)

@Serializable
data class XmpRecording(
    val id: String,
    val title: String? = null,
    val channelName: String? = null,
    val folder: String? = null,
    val status: String? = null,
    val startAt: Long? = null,
    /** The scheduled window. */
    val durationMs: Long? = null,
    /** What was actually captured (seconds of video), when known. */
    val mediaSeconds: Double? = null,
    val totalBytes: Long? = null,
    val identity: String? = null,
    val key: String? = null,
    /** TMDB image paths of the show or film it recorded, when XMPlayer identified it. */
    val poster: String? = null,
    val backdrop: String? = null
)

@Serializable
internal data class XmpRecordingsResponse(val recordings: List<XmpRecording> = emptyList())

@Serializable
internal data class XmpRecordingPlayback(val id: String? = null, val playlist: String, val exp: Long? = null)

/** A Source Editor edit, exactly as XMPlayer applies it (the Sync API's intents). */
sealed interface XmpCatalogEdit {
    fun toJson(): JsonObject

    data class HideCategories(val ids: List<String>, val hidden: Boolean) : XmpCatalogEdit {
        override fun toJson() = buildJsonObject {
            put("type", "hideCategories"); put("ids", stringArray(ids)); put("hidden", hidden)
        }
    }

    data class FavoriteCategories(val ids: List<String>, val on: Boolean) : XmpCatalogEdit {
        override fun toJson() = buildJsonObject {
            put("type", "favoriteCategories"); put("ids", stringArray(ids)); put("on", on)
        }
    }

    /** An empty name gives the category its provider name back. */
    data class RenameCategory(val id: String, val name: String) : XmpCatalogEdit {
        override fun toJson() = buildJsonObject { put("type", "renameCategory"); put("id", id); put("name", name) }
    }

    /** [order] is every category id of the tab, in the new order. */
    data class ReorderCategories(val order: List<String>) : XmpCatalogEdit {
        override fun toJson() = buildJsonObject { put("type", "reorderCategories"); put("order", stringArray(order)) }
    }

    data class HideTitles(val ids: List<String>, val hidden: Boolean) : XmpCatalogEdit {
        override fun toJson() = buildJsonObject {
            put("type", "hideTitles"); put("ids", stringArray(ids)); put("hidden", hidden)
        }
    }

    data class RenameTitle(val id: String, val name: String, val providerName: String) : XmpCatalogEdit {
        override fun toJson() = buildJsonObject {
            put("type", "renameTitle"); put("id", id); put("name", name); put("providerName", providerName)
        }
    }
}

private fun stringArray(values: List<String>) =
    kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })

class XmpCatalogClient(private val api: XmpApiClient) {
    internal suspend fun sources(): List<XmpCatalogSource> =
        api.decode<XmpCatalogSourcesResponse>(api.get("/v1/catalog").body).sources

    suspend fun categories(sourceId: String, kind: XmpCatalogKind): List<XmpCatalogCategory> =
        api.decode<XmpCatalogCategoriesResponse>(api.get(path(sourceId, kind)).body).categories

    /** One page (1-based) of a category's titles, up to 200. */
    suspend fun titles(sourceId: String, kind: XmpCatalogKind, categoryId: String, page: Int, limit: Int): List<XmpCatalogTitle> =
        api.decode<XmpCatalogTitlesResponse>(
            api.get(path(sourceId, kind), mapOf("category" to categoryId, "page" to page.toString(), "limit" to limit.toString())).body
        ).titles

    /** Applies one edit; answers what XMPlayer did ("hid 1 category"). */
    suspend fun edit(sourceId: String, kind: XmpCatalogKind, edit: XmpCatalogEdit): String {
        val body = buildJsonObject { put("intent", edit.toJson()) }.toString()
        val result = api.decode<XmpCatalogEditResponse>(api.post("${path(sourceId, kind)}/edits", body).body)
        return result.did ?: ""
    }

    suspend fun recordings(): List<XmpRecording> =
        api.decode<XmpRecordingsResponse>(api.get("/v1/dvr", mapOf("status" to "completed")).body).recordings

    /** A 6-hour HLS link for one finished recording. */
    suspend fun recordingPlaylist(recordingId: String): String =
        api.decode<XmpRecordingPlayback>(api.get("/v1/dvr/${segment(recordingId)}/playback").body).playlist

    private fun path(sourceId: String, kind: XmpCatalogKind) = "/v1/catalog/${segment(sourceId)}/${kind.wire}"

    private fun segment(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** The error code the Sync API answered with, for messages ("no_answering_device", "premium_required", …). */
fun Throwable.xmpErrorCode(): String? = (this as? XmpApiException)?.code
