package com.nuvio.tv.data.mediaserver.xmplayer

import android.os.SystemClock
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerItemDetails
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPage
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.domainType
import com.nuvio.tv.data.xmplayer.XmpApiClient
import com.nuvio.tv.data.xmplayer.XmpApiException
import com.nuvio.tv.data.xmplayer.XmpAuthException
import com.nuvio.tv.data.xmplayer.XmpAuthStore
import com.nuvio.tv.data.xmplayer.XmpCatalogClient
import com.nuvio.tv.data.xmplayer.XmpCatalogKind
import com.nuvio.tv.data.xmplayer.XmpCatalogTitle
import com.nuvio.tv.data.xmplayer.XmpRecording
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The connected XMPlayer profile as a Nuvio media server. It "airs" the profile's curated IPTV catalog: every category
 * shown in XMPlayer is a library (the ones marked favourite are rows on Home), and its titles open by their TMDB / IMDb
 * ids — Nuvio's own title page and streams, nothing is restreamed. Hidden and unmatched titles are not aired. The
 * profile's finished Cloud DVR recordings are one more library, and they play from XMPlayer's own HLS link.
 *
 * Signing in uses the XMPlayer connection Nuvio already has (Settings ▸ Tracking): the server token Nuvio stores and
 * syncs for a media server is only a placeholder, so no XMPlayer credential ever reaches Nuvio's own sync.
 */
class XmpServerProvider(
    private val catalog: XmpCatalogClient,
    private val api: XmpApiClient,
    private val auth: XmpAuthStore
) : ServerProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "XMPlayer"
    override val capabilities: Set<ServerCapability> = emptySet()
    override val minimumVersion: String = "1"

    override suspend fun signIn(address: String, username: String, password: String): ServerSignIn = guarded {
        if (!auth.state.value.isAuthenticated) throw ServerException(ServerFailure.AUTH_REQUIRED)
        val account = auth.state.value.account ?: api.refreshAccount()
        ServerSignIn(
            address = "xmplayer.app",
            serverName = "XMPlayer",
            serverId = PROVIDER_ID,
            userId = account.profileId,
            userName = account.profileName ?: "XMPlayer",
            token = PLACEHOLDER_TOKEN
        )
    }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> = guarded {
        mutableReading.value = XmpReadProgress(done = 0, total = 0)
        try {
            readLibraries(session)
        } finally {
            mutableReading.value = null
        }
    }

    /** Every playlist's movies and series, then the recordings — one step each, published as it goes (see [reading]). */
    private suspend fun readLibraries(session: ServerSession): List<ServerLibrary> {
        clearAired()
        val sources = try {
            catalog.sources()
        } catch (error: XmpApiException) {
            // The playlists reach Nuvio only through the account's XMPlayer DVR agent; the recordings do not. While no
            // agent answers, a FIRST add still adds XMPlayer (with its recordings) and its page says what is missing and
            // how to retry. A re-read of a server that already airs categories fails instead: the rows on Home are kept,
            // never emptied because the agent is away for a while.
            val airsCategories = session.connection.libraries.any { LibraryId.parse(it.id) is LibraryId.Category }
            if (airsCategories || !error.isAgentAway()) throw error
            emptyList()
        }
        val many = sources.size > 1
        val total = sources.size * XmpCatalogKind.entries.size + 1
        var done = 0
        val out = mutableListOf<ServerLibrary>()
        for (source in sources) {
            for (kind in XmpCatalogKind.entries) {
                mutableReading.value = XmpReadProgress(done, total, source.name, kind)
                val categories = runCatching { catalog.categories(source.id, kind) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    if ((error as? XmpApiException)?.code == "source_has_no_vod") emptyList() else throw error
                }
                categories.filterNot { it.hidden }.forEach { category ->
                    out += ServerLibrary(
                        id = LibraryId.Category(source.id, kind, category.id).encode(),
                        name = if (many) "${source.name} · ${category.name}" else category.name,
                        kind = if (kind == XmpCatalogKind.MOVIES) ServerMediaKind.MOVIE else ServerMediaKind.SERIES,
                        home = category.favorite
                    )
                }
                done++
            }
        }
        mutableReading.value = XmpReadProgress(done, total, recordings = true)
        val recordings = runCatching { catalog.recordings() }.getOrNull()
        if (recordings != null) {
            // One video each, opened as itself: a single type keeps the row titled "Recordings" (a mixed library reads "- Mixed").
            out += ServerLibrary(id = LibraryId.Recordings.encode(), name = RECORDINGS_NAME, kind = ServerMediaKind.MOVIE, home = true)
        }
        mutableReading.value = XmpReadProgress(total, total)
        return out
    }

    override suspend fun libraryPage(session: ServerSession, library: ServerLibrary, start: Int, limit: Int): ServerPage<ServerTitle> = guarded {
        when (val ref = LibraryId.parse(library.id)) {
            is LibraryId.Recordings -> {
                val all = catalog.recordings()
                rememberRecordings(all)
                ServerPage(all.drop(start).take(limit).map { recordingTitle(session, it) }, all.size)
            }
            is LibraryId.Category -> airedPage(session, ref, start, limit)
            null -> throw ServerException(ServerFailure.NOT_FOUND)
        }
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails = guarded {
        when (val item = ItemId.parse(itemId)) {
            is ItemId.Recording -> {
                val recording = recordingsById[item.id] ?: catalog.recordings().also(::rememberRecordings).firstOrNull { it.id == item.id }
                    ?: throw ServerException(ServerFailure.NOT_FOUND)
                ServerItemDetails(recordingMeta(session, recording), TrackingExternalIds())
            }
            is ItemId.Title -> {
                val title = titlesById[itemId] ?: throw ServerException(ServerFailure.NOT_FOUND)
                ServerItemDetails(titleMeta(session, item, title), TrackingExternalIds(imdb = title.imdb, tmdb = title.tmdb))
            }
            null -> throw ServerException(ServerFailure.NOT_FOUND)
        }
    }

    /** A recording plays from XMPlayer; a catalog title has no stream here (Nuvio's own addons play it by its ids). */
    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> {
        val item = ItemId.parse(itemId) as? ItemId.Recording ?: return emptyList()
        val recording = recordingsById[item.id]
        return listOf(
            ServerCandidate(
                target = ServerPlaybackTarget(ServerItemRef(session.connection.id, itemId), mediaSourceId = RECORDING_SOURCE),
                title = "XMPlayer DVR",
                description = recording?.let(::recordingLine),
                filename = null,
                sizeBytes = recording?.totalBytes?.takeIf { it > 0 }
            )
        )
    }

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession = guarded {
        val item = ItemId.parse(request.target.item.itemId) as? ItemId.Recording ?: throw ServerException(ServerFailure.UNSUPPORTED)
        ServerPlaybackSession(
            target = request.target,
            mediaSourceId = RECORDING_SOURCE,
            url = catalog.recordingPlaylist(item.id),
            headers = emptyMap(),
            subtitles = emptyList(),
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_STREAM
        )
    }

    /** Forgets the aired pages, after the catalog was edited (here or in XMPlayer). */
    fun clearAired() {
        aired.clear()
    }

    // ── aired catalog pages ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Nuvio pages a library by item offset; the API pages a category by its own pages, and only some of its titles are
     * aired (shown and matched). So the aired titles of a category are collected in order, a few API pages at a time,
     * and Nuvio's offsets index into that list — every offset lands on the same title.
     */
    private class Aired(val at: Long) {
        val mutex = Mutex()
        val titles = mutableListOf<XmpCatalogTitle>()
        var nextPage = 1
        var exhausted = false
    }

    private val mutableReading = MutableStateFlow<XmpReadProgress?>(null)

    /**
     * What a running read of the libraries is doing, step by step (each playlist's movies, its series, then the
     * recordings); null when none runs. Adding XMPlayer in Settings shows it, since that read takes seconds.
     */
    val reading: StateFlow<XmpReadProgress?> = mutableReading.asStateFlow()

    private val aired = ConcurrentHashMap<String, Aired>()
    private val titlesById = ConcurrentHashMap<String, XmpCatalogTitle>()
    private val recordingsById = ConcurrentHashMap<String, XmpRecording>()

    private suspend fun airedPage(session: ServerSession, ref: LibraryId.Category, start: Int, limit: Int): ServerPage<ServerTitle> {
        val key = ref.encode()
        val cached = aired[key]?.takeIf { SystemClock.elapsedRealtime() - it.at < AIRED_TTL_MS }
        val state = cached ?: Aired(SystemClock.elapsedRealtime()).also { aired[key] = it }
        state.mutex.withLock {
            while (state.titles.size < start + limit && !state.exhausted) {
                val page = catalog.titles(ref.sourceId, ref.kind, ref.categoryId, state.nextPage, API_PAGE)
                state.nextPage += 1
                if (page.size < API_PAGE) state.exhausted = true
                state.titles += page.filter { !it.hidden && it.isMatched }
            }
        }
        val slice = state.titles.drop(start).take(limit)
        val items = slice.map { title ->
            val itemId = ItemId.Title(ref.sourceId, ref.kind, title.id).encode()
            titlesById[itemId] = title
            ServerTitle(
                preview = MetaPreview(
                    id = ServerItemRef(session.connection.id, itemId).encode(),
                    type = if (ref.kind == XmpCatalogKind.MOVIES) ContentType.MOVIE else ContentType.SERIES,
                    name = title.name,
                    poster = title.poster?.let(::posterUrl),
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = title.year,
                    imdbRating = null,
                    genres = emptyList(),
                    imdbId = title.imdb
                ),
                externalIds = TrackingExternalIds(imdb = title.imdb, tmdb = title.tmdb)
            )
        }
        return ServerPage(items, if (state.exhausted) state.titles.size else null)
    }

    // ── recordings ───────────────────────────────────────────────────────────────────────────────────────────

    private fun rememberRecordings(list: List<XmpRecording>) {
        list.forEach { recordingsById[it.id] = it }
    }

    /** A recording keeps its own id (not the show's ids), so opening it opens the recording, never the show's page. */
    private fun recordingTitle(session: ServerSession, recording: XmpRecording) = ServerTitle(
        preview = MetaPreview(
            id = ServerItemRef(session.connection.id, ItemId.Recording(recording.id).encode()).encode(),
            type = ContentType.MOVIE,
            name = recording.title ?: RECORDINGS_NAME,
            // Every recording is a wide card (a TV capture), so a row never mixes shapes: the show's backdrop when
            // XMPlayer identified it, else a plain card with its title.
            poster = recording.backdrop?.let(::backdropUrl),
            posterShape = PosterShape.LANDSCAPE,
            background = recording.backdrop?.let(::backdropUrl),
            landscapePoster = recording.backdrop?.let(::backdropUrl),
            logo = null,
            description = recordingLine(recording),
            releaseInfo = recording.startAt?.let(::dateOf),
            imdbRating = null,
            genres = emptyList()
        )
    )

    private fun recordingMeta(session: ServerSession, recording: XmpRecording) = meta(
        id = ServerItemRef(session.connection.id, ItemId.Recording(recording.id).encode()).encode(),
        type = ContentType.MOVIE,
        name = recording.title ?: RECORDINGS_NAME,
        description = recordingLine(recording),
        releaseInfo = recording.startAt?.let(::dateOf),
        runtime = recording.lengthMinutes()?.let { "$it min" },
        poster = recording.poster?.let(::posterUrl),
        background = recording.backdrop?.let(::backdropUrl)
    )

    private fun titleMeta(session: ServerSession, item: ItemId.Title, title: XmpCatalogTitle) = meta(
        id = ServerItemRef(session.connection.id, item.encode()).encode(),
        type = if (item.kind == XmpCatalogKind.MOVIES) ContentType.MOVIE else ContentType.SERIES,
        name = title.name,
        description = null,
        releaseInfo = title.year,
        runtime = null,
        poster = title.poster?.let(::posterUrl),
        background = null
    )

    private fun meta(
        id: String, type: ContentType, name: String, description: String?, releaseInfo: String?, runtime: String?,
        poster: String?, background: String?
    ) = Meta(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = if (poster == null) PosterShape.LANDSCAPE else PosterShape.POSTER,
        background = background,
        logo = null,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = emptyList(),
        runtime = runtime,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private fun recordingLine(recording: XmpRecording): String = listOfNotNull(
        recording.channelName,
        recording.startAt?.let(::dateOf),
        recording.lengthMinutes()?.let { "$it min" }
    ).joinToString(" · ")

    /** The captured length when XMPlayer knows it (a cut recording is shorter than its scheduled window). */
    private fun XmpRecording.lengthMinutes(): Long? =
        mediaSeconds?.takeIf { it > 0 }?.let { kotlin.math.max(1L, (it / 60).toLong()) } ?: durationMs?.takeIf { it > 0 }?.let { it / 60_000 }

    private fun dateOf(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

    private fun posterUrl(path: String): String = if (path.startsWith("http")) path else TMDB_POSTER + path

    private fun backdropUrl(path: String): String = if (path.startsWith("http")) path else TMDB_BACKDROP + path

    // ── errors ───────────────────────────────────────────────────────────────────────────────────────────────

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ServerException) {
        throw error
    } catch (error: Exception) {
        throw ServerException(error.toServerFailure())
    }

    companion object {
        const val PROVIDER_ID = "xmplayer"
        private const val PLACEHOLDER_TOKEN = "xmplayer-connection"
        private const val RECORDINGS_NAME = "Recordings"
        private const val RECORDING_SOURCE = "dvr"
        private const val API_PAGE = 200
        private const val AIRED_TTL_MS = 10 * 60_000L
        private const val TMDB_POSTER = "https://image.tmdb.org/t/p/w342"
        private const val TMDB_BACKDROP = "https://image.tmdb.org/t/p/w1280"
    }
}

/** No XMPlayer DVR agent of the account can answer right now: none at all, none online, or one still starting. */
internal fun XmpApiException.isAgentAway(): Boolean =
    code == "no_agent" || code == "no_answering_device" || code == "agent_offline" || code == "agent_not_ready" || status == 503

/** How an XMPlayer answer reads as a media-server failure (the editor shows its own, more exact messages). */
fun Throwable.toServerFailure(): ServerFailure = when (this) {
    is ServerException -> failure
    is XmpAuthException -> ServerFailure.AUTH_REQUIRED
    is XmpApiException -> when {
        code == "scope_required" || status == 401 -> ServerFailure.AUTH_REQUIRED
        code == "premium_required" || code == "source_not_in_profile" || status == 403 -> ServerFailure.FORBIDDEN
        code == "no_answering_device" || code == "agent_offline" || status == 503 -> ServerFailure.UNREACHABLE
        code == "no_agent" -> ServerFailure.UNSUPPORTED
        status == 404 -> ServerFailure.NOT_FOUND
        else -> ServerFailure.FAILED
    }
    else -> ServerFailure.FAILED
}

/**
 * One step of reading XMPlayer's libraries: [done] of [total] steps finished, and the one under way — a playlist's
 * movies or series ([sourceName], [kind]) or the [recordings]. [total] is 0 while the playlists themselves are read.
 */
data class XmpReadProgress(
    val done: Int,
    val total: Int,
    val sourceName: String? = null,
    val kind: XmpCatalogKind? = null,
    val recordings: Boolean = false
) {
    /** The share finished, 0..1; null while the number of steps is not known yet. */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** A library: one category of one playlist's movies or series, or the recordings. */
internal sealed interface LibraryId {
    fun encode(): String

    data class Category(val sourceId: String, val kind: XmpCatalogKind, val categoryId: String) : LibraryId {
        override fun encode() = "cat|$sourceId|${kind.wire}|$categoryId"
    }

    data object Recordings : LibraryId {
        override fun encode() = "dvr"
    }

    companion object {
        fun parse(id: String): LibraryId? {
            if (id == "dvr") return Recordings
            val parts = id.split('|')
            if (parts.size != 4 || parts[0] != "cat") return null
            val kind = XmpCatalogKind.entries.firstOrNull { it.wire == parts[2] } ?: return null
            return Category(parts[1], kind, parts[3])
        }
    }
}

/** An item: a catalog title, or a recording. */
internal sealed interface ItemId {
    fun encode(): String

    data class Title(val sourceId: String, val kind: XmpCatalogKind, val titleId: String) : ItemId {
        override fun encode() = "t|$sourceId|${kind.wire}|$titleId"
    }

    data class Recording(val id: String) : ItemId {
        override fun encode() = "r|$id"
    }

    companion object {
        fun parse(id: String): ItemId? {
            val parts = id.split('|')
            return when {
                parts.size == 2 && parts[0] == "r" -> Recording(parts[1])
                parts.size == 4 && parts[0] == "t" ->
                    XmpCatalogKind.entries.firstOrNull { it.wire == parts[2] }?.let { Title(parts[1], it, parts[3]) }
                else -> null
            }
        }
    }
}
