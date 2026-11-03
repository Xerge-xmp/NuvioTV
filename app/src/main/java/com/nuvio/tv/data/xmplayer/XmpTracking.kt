package com.nuvio.tv.data.xmplayer

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ── writing: POST /v1/events (Premium / Pro) ────────────────────────────────────────────────────────────────

/** Writes need a paid XMPlayer plan; a Free connection reads only. */
class XmpPlanRequiredException : Exception("XMPlayer Premium or Pro is needed to save changes")

@Serializable
internal data class XmpScrobbleResponse(
    val watched: Boolean? = null,
    val progress: Double? = null,
    val result: XmpWriteResult? = null
)

@Serializable
internal data class XmpWriteResult(
    val ok: Boolean = false,
    val key: String? = null,
    val ids: XmpIds? = null,
    val title: String? = null,
    val year: String? = null,
    val error: String? = null
)

@Singleton
class XmpWriter @Inject constructor(
    private val api: XmpApiClient,
    private val sync: XmpSyncRepository
) {
    /** Up to 50 events a request, each applied on its own; one result per event, in order. */
    internal suspend fun events(scope: XmpAuthScope, events: List<JsonObject>): List<XmpWriteResult> {
        if (events.isEmpty()) return emptyList()
        if (!sync.canWrite()) throw XmpPlanRequiredException()
        val out = ArrayList<XmpWriteResult>(events.size)
        for (chunk in events.chunked(50)) {
            val body = buildJsonObject { put("events", buildJsonArray { chunk.forEach { add(it) } }) }
            val response = api.decode<XmpEventsWithIdentity>(api.post("/v1/events", body.toString(), scope).body)
            val byIndex = response.results.associate { (it.index ?: -1) to it.toResult() }
            chunk.indices.forEach { i -> out += byIndex[i] ?: XmpWriteResult(ok = false, error = "missing_result") }
        }
        return out
    }
}

@Serializable
internal data class XmpEventsWithIdentity(val results: List<XmpIndexedWriteResult> = emptyList())

@Serializable
internal data class XmpIndexedWriteResult(
    val index: Int? = null,
    val ok: Boolean = false,
    val key: String? = null,
    val ids: XmpIds? = null,
    val title: String? = null,
    val year: String? = null,
    val error: String? = null
) {
    fun toResult() = XmpWriteResult(ok, key, ids, title, year, error)
}

/** The media a write result names (its ids are XMPlayer's identification of the item that was sent). */
private fun XmpWriteResult.media(type: String): XmpMedia? {
    val ids = ids ?: return null
    if (ids.tmdb == null && ids.imdb == null) return null
    return XmpMedia(type = type, tmdb = ids.tmdb, imdb = ids.imdb, tvdb = ids.tvdb, mal = ids.mal, anilist = ids.anilist, title = title, year = year)
}

private fun isIdentityMiss(error: String?) = error != null && (error.startsWith("identity_") || error == "not_found")

// ── the provider ─────────────────────────────────────────────────────────────────────────────────────────────

@Singleton
class XmpTrackingProvider @Inject constructor(
    auth: XmpAuthStore,
    profiles: ProfileManager,
    override val scrobbler: XmpTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.XMPLAYER,
        "XMPlayer",
        setOf(
            TrackingCapability.AUTHENTICATION, TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ, TrackingCapability.WATCHED_WRITE, TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE, TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}

/**
 * What Nuvio plays reaches XMPlayer as it plays: start, about once a minute while it plays (the coordinator's
 * cadence), pause, stop. XMPlayer decides "watched" (95%) the same way for every app. Continue Watching here moves
 * at once from the answer, before the next change read.
 */
@Singleton
class XmpTrackingScrobbler @Inject constructor(
    private val auth: XmpAuthStore,
    private val api: XmpApiClient,
    private val sync: XmpSyncRepository
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.XMPLAYER

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!auth.state.value.isAuthenticated) return
        if (!sync.canWrite()) {
            Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "xmplayer adapter skipped action=${action.wireValue} reason=read_only_plan")
            return
        }
        if (!event.progressPercent.isFinite()) return
        val item = event.media.toXmpItem() ?: return
        val scope = sync.currentScope()
        val fraction = (event.progressPercent / 100.0).coerceIn(0.0, 1.0)
        val body = buildJsonObject {
            put("item", item)
            put("progress", fraction)
        }
        Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "xmplayer adapter action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}")
        val response = api.decode<XmpScrobbleResponse>(api.post("/v1/scrobble/${action.wireValue}", body.toString(), scope).body)
        val result = response.result?.takeIf { it.ok && it.key != null } ?: return
        val episode = event.media.episode
        val media = result.media(if (episode != null) "series" else "movie") ?: return
        val progress = (response.progress ?: fraction).coerceIn(0.0, 1.0)
        sync.applyLocal(scope) { snapshot ->
            val key = result.key!!
            val previous = snapshot.history[key]
            val entry = (previous ?: XmpHistoryEntry(key = key, media = media, season = episode?.season, episode = episode?.number))
                .copy(progress = progress, completed = response.watched == true || progress >= XMP_COMPLETED, lastWatchedAt = System.currentTimeMillis())
            snapshot.copy(history = snapshot.history + (key to entry))
        }
    }
}

/** Watched / unwatched marks: `watched` events. A whole-series mark is never sent (it would mark every episode). */
@Singleton
class XmpTrackingHistoryWriter @Inject constructor(
    private val sync: XmpSyncRepository,
    private val writer: XmpWriter
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.XMPLAYER

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult =
        mark(profileId, items.filterNot(TrackingHistoryItem::isWholeSeriesMark).map { it.media }, watched = true)

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult =
        mark(profileId, items.filterNot { it.episode == null && it.kind != com.nuvio.tv.core.tracking.TrackingMediaKind.MOVIE }, watched = false)

    private suspend fun mark(profileId: Int, items: List<TrackingMediaReference>, watched: Boolean): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId || items.isEmpty()) return TrackingMutationResult(0)
        val sendable = items.mapNotNull { media -> media.toXmpItem()?.let { media to it } }
        val events = sendable.map { (_, item) ->
            buildJsonObject { put("type", "watched"); put("item", item); put("watched", watched) }
        }
        val results = writer.events(scope, events)
        sync.applyLocal(scope) { snapshot ->
            var history = snapshot.history
            results.forEachIndexed { i, r ->
                val key = r.key ?: return@forEachIndexed
                if (!r.ok) return@forEachIndexed
                val media = sendable[i].first
                val existing = history[key]
                val entry = existing ?: r.media(if (media.episode != null) "series" else "movie")?.let {
                    XmpHistoryEntry(key = key, media = it, season = media.episode?.season, episode = media.episode?.number)
                } ?: return@forEachIndexed
                history = history + (key to entry.copy(
                    completed = watched,
                    progress = if (watched) 1.0 else 0.0,
                    lastWatchedAt = if (watched) System.currentTimeMillis() else entry.lastWatchedAt
                ))
            }
            snapshot.copy(history = history)
        }
        return TrackingMutationResult(
            attemptedCount = items.size,
            notFoundCount = (items.size - sendable.size) + results.count { !it.ok && isIdentityMiss(it.error) }
        )
    }
}

// ── progress ──────────────────────────────────────────────────────────────────────────────────────────────────

@Singleton
class XmpTrackingProgressProvider @Inject constructor(
    private val sync: XmpSyncRepository,
    private val writer: XmpWriter,
    auth: XmpAuthStore,
    profiles: ProfileManager,
    layout: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.XMPLAYER
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.distinctUntilChanged()
    private val projection = combine(sync.progressState, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId && state.scope == authorization.scope) state.projection
        else XmpProgressProjection.Empty
    }.distinctUntilChanged()
    override val allProgress = projection.map { it.progress }.loadOnStart()
    override val watchedItems = projection.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds = combine(projection, layout.nextUpFromFurthestEpisode) { p, furthest -> p.nextUp(furthest) }.loadOnStart()
    override val remoteProgressLoaded = combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        state.hasLoaded && state.scope == authorization.scope && authorization.scope.profileId == profileId && authorization.isAuthenticated
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projection.map { it.episodeProgress(contentId) }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { it.isWatched(contentId, season, episode) }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        return sync.currentProjection().watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        sync.ensureLoaded()
        return sync.currentProjection().showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (retainsLocalProgress(parent)) return null
        return sync.currentProjection().isWatched(parent, season, episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    /** "Remove from Continue Watching": the item leaves XMPlayer's history (a `remove` event), as it does in XMPlayer. */
    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val scope = sync.currentScope()
        val entries = sync.currentProjection().entriesFor(contentId, season, episode).filter { !it.completed }
        if (entries.isEmpty()) return
        val events = entries.map { e ->
            buildJsonObject {
                put("type", "remove")
                put("item", buildJsonObject {
                    put("type", if (e.isEpisode) "episode" else "movie")
                    e.media.tmdb?.let { put("tmdb", it) }
                    e.media.imdb?.let { put("imdb", it) }
                    e.season?.let { put("season", it) }
                    e.episode?.let { put("episode", it) }
                    e.media.title?.let { put(if (e.isEpisode) "seriesTitle" else "title", it) }
                })
            }
        }
        val results = writer.events(scope, events)
        val removed = entries.filterIndexed { i, _ -> results.getOrNull(i)?.ok == true }.map { it.key }.toSet()
        if (removed.isNotEmpty()) sync.applyLocal(scope) { s -> s.copy(history = s.history - removed) }
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = sync.currentProjection().isHidden(contentId)
    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress

    /** A title with no id XMPlayer can identify stays in Nuvio's own progress (it could not be sent). */
    override fun retainsLocalProgress(contentId: String): Boolean = !parseTrackingExternalIds(contentId).hasAny
    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}

// ── library ───────────────────────────────────────────────────────────────────────────────────────────────────

internal const val XMP_FAVORITES_KEY = "xmplayer:favorites"
internal const val XMP_WATCHLIST_KEY = "xmplayer:watchlist"
internal const val XMP_COLLECTION_PREFIX = "xmplayer:collection:"

/**
 * The library from XMPlayer: Favourites (XMPlayer's bookmarks — what "add to library" means there, so it is this
 * provider's default list), the Watchlist, and each of the profile's collections, in the user's order. Adding and
 * removing writes `favorite`, `watchlist` and `collection` events.
 */
@Singleton
class XmpTrackingLibraryProvider @Inject constructor(
    private val sync: XmpSyncRepository,
    private val writer: XmpWriter,
    tracking: XmpTrackingProvider
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.XMPLAYER
    override val isAuthenticated = tracking.isAuthenticated
    override val isRefreshing = sync.state.map { it.isLoading }.distinctUntilChanged()
    private val snapshots = sync.state.map { it.snapshot?.takeIf { s -> s.isInitialized } }.distinctUntilChanged()
    private val projection = snapshots.map { XmpLibraryProjection(it) }
    override val items: Flow<List<LibraryEntry>> = projection.map { it.entries }.onStart { sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC) }.distinctUntilChanged()
    override val tabs: Flow<List<LibraryListTab>> = projection.map { it.tabs }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = key.startsWith("xmplayer:")

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
        projection.map { it.membership(setOf(itemId), itemType) }.distinctUntilChanged()

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership + (XMP_FAVORITES_KEY to (currentMembership[XMP_FAVORITES_KEY] != true))

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        sync.ensureLoaded()
        val p = XmpLibraryProjection(sync.currentSnapshot())
        val member = p.membership(item.aliases(), item.itemType)
        return ListMembershipSnapshot(p.tabs.filter { it.isMembershipDestination }.associate { it.key to (it.key in member) })
    }

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges, destructiveRemovalConfirmed: Boolean) {
        val scope = sync.currentScope()
        val current = getMembershipSnapshot(item).listMembership
        val target = item.toXmpItem()
        val wanted = changes.desiredMembership.filter { (key, want) -> current[key] != want && recognizesListKey(key) }
        if (wanted.isEmpty()) return
        val events = wanted.map { (key, want) ->
            when {
                key == XMP_FAVORITES_KEY -> buildJsonObject { put("type", "favorite"); put("item", target); put("favorite", want) }
                key == XMP_WATCHLIST_KEY -> buildJsonObject { put("type", "watchlist"); put("item", target); put("watchlist", want) }
                else -> buildJsonObject {
                    put("type", "collection")
                    put("collection", key.removePrefix(XMP_COLLECTION_PREFIX))
                    put("item", target)
                    put("member", want)
                }
            }
        }
        val results = writer.events(scope, events)
        results.firstOrNull { !it.ok }?.let { throw XmpApiException(code = it.error ?: "write_failed") }
        // The change read confirms it and fills in what XMPlayer knows about the title.
        sync.refresh(TrackingRefreshIntent.INVALIDATED)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)
}

internal class XmpLibraryProjection(snapshot: XmpSyncSnapshot?) {
    val tabs: List<LibraryListTab>
    val entries: List<LibraryEntry>
    private val keysByAlias = mutableMapOf<Pair<String, String>, MutableSet<String>>()

    init {
        val lists = linkedMapOf<String, List<XmpListEntry>>()
        val tabList = mutableListOf<LibraryListTab>()
        if (snapshot != null) {
            lists[XMP_FAVORITES_KEY] = snapshot.favorites
            tabList += LibraryListTab(XMP_FAVORITES_KEY, "Favourites", LibraryListTab.Type.PERSONAL, trackingProviderId = XMP_PROVIDER_ID)
            lists[XMP_WATCHLIST_KEY] = snapshot.collectionsOf("watchlist").flatMap { it.items }
            tabList += LibraryListTab(XMP_WATCHLIST_KEY, "Watchlist", LibraryListTab.Type.WATCHLIST, trackingProviderId = XMP_PROVIDER_ID)
            for (c in snapshot.collectionsOf("collection")) {
                val key = XMP_COLLECTION_PREFIX + c.id
                lists[key] = c.items
                tabList += LibraryListTab(
                    key = key,
                    title = c.name?.takeIf(String::isNotBlank) ?: "Collection",
                    type = LibraryListTab.Type.PERSONAL,
                    trackingProviderId = XMP_PROVIDER_ID,
                    supportedContentTypes = when (c.type) { "movies" -> setOf("movie"); "series" -> setOf("series"); else -> null }
                )
            }
        }
        tabs = tabList
        val combined = linkedMapOf<String, LibraryEntry>()
        lists.forEach { (listKey, items) ->
            items.forEachIndexed { rank, item ->
                val m = item.media
                val type = if (m.type == "movie") "movie" else "series"
                val id = m.contentId
                val previous = combined["$type:$id"]
                combined["$type:$id"] = LibraryEntry(
                    id = id,
                    type = type,
                    name = m.title ?: previous?.name ?: id,
                    poster = m.poster ?: previous?.poster,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = m.year,
                    imdbRating = null,
                    genres = emptyList(),
                    addonBaseUrl = null,
                    listKeys = previous?.listKeys.orEmpty() + listKey,
                    listRanks = previous?.listRanks.orEmpty() + (listKey to rank),
                    listedAt = maxOf(previous?.listedAt ?: 0L, item.addedAt),
                    imdbId = m.imdb,
                    tmdbId = m.tmdb?.toIntOrNull(),
                    trackingProviderId = XMP_PROVIDER_ID,
                    trackingProviderItemId = item.key,
                    rawPosterUrl = m.poster ?: previous?.rawPosterUrl
                )
                m.aliases().forEach { alias -> keysByAlias.getOrPut(type to alias) { mutableSetOf() } += listKey }
            }
        }
        entries = combined.values.toList()
    }

    fun membership(ids: Set<String>, itemType: String): Set<String> {
        val type = if (itemType.equals("movie", ignoreCase = true)) "movie" else "series"
        return ids.flatMapTo(mutableSetOf()) { keysByAlias[type to it].orEmpty() }
    }
}
