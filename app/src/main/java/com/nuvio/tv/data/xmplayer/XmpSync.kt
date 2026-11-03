package com.nuvio.tv.data.xmplayer

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── The snapshot: what this device knows of the connected XMPlayer profile ──────────────────────────────────

/** One movie or episode of the profile's watch history. [progress] is a fraction (0–1); [completed] from 0.95. */
@Serializable
data class XmpHistoryEntry(
    val key: String,
    val media: XmpMedia,
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Double = 0.0,
    val completed: Boolean = false,
    val lastWatchedAt: Long = 0L
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/** A title on a list (favourites, a watchlist, a collection). */
@Serializable
data class XmpListEntry(
    val key: String,
    val media: XmpMedia,
    val addedAt: Long = 0L
)

/** watchlist | dropped | hidden | collection — one per type (movies / series) for the first three. */
@Serializable
data class XmpCollection(
    val id: String,
    val kind: String,
    val name: String? = null,
    val type: String? = null,
    val items: List<XmpListEntry> = emptyList()
)

enum class XmpLoadPhase { HISTORY, FAVORITES, COLLECTIONS }

@Serializable
data class XmpSyncSnapshot(
    val xmpProfileId: String,
    val cursor: Long? = null,
    /** The cursor of the full load in progress (changes from here on are replayed once it completes). */
    val loadCursor: Long? = null,
    val loadedPhases: Set<XmpLoadPhase> = emptySet(),
    val history: Map<String, XmpHistoryEntry> = emptyMap(),
    val favorites: List<XmpListEntry> = emptyList(),
    val collections: List<XmpCollection> = emptyList(),
    val checkedAtEpochMs: Long? = null,
    val isInitialized: Boolean = false
) {
    fun collectionsOf(kind: String): List<XmpCollection> = collections.filter { it.kind == kind }

    /** A snapshot saved before id-less titles were left out, without them. */
    fun withoutUnidentified(): XmpSyncSnapshot = copy(
        history = history.filterValues { it.media.hasId },
        favorites = favorites.filter { it.media.hasId },
        collections = collections.map { c -> c.copy(items = c.items.filter { it.media.hasId }) }
    )
}

enum class XmpSyncError { UNAVAILABLE, INVALID_RESPONSE, RATE_LIMIT, PLAN_REQUIRED, AUTHORIZATION_REVOKED }

data class XmpSyncState(
    val scope: XmpAuthScope? = null,
    val snapshot: XmpSyncSnapshot? = null,
    val isLoading: Boolean = false,
    val error: XmpSyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null,
    val isLive: Boolean = false
) {
    val hasLoaded: Boolean get() = snapshot?.isInitialized == true
    val checkedAtEpochMs: Long? get() = snapshot?.checkedAtEpochMs
}

internal data class XmpProgressState(
    val scope: XmpAuthScope? = null,
    val projection: XmpProgressProjection = XmpProgressProjection.Empty
)

internal fun Throwable.toXmpSyncError(): XmpSyncError = when {
    this is XmpAuthException -> XmpSyncError.AUTHORIZATION_REVOKED
    this is XmpDecodingException -> XmpSyncError.INVALID_RESPONSE
    this is XmpApiException && status == 429 -> XmpSyncError.RATE_LIMIT
    this is XmpApiException && status == 403 && code in setOf("plan_required", "read_only", "write_not_allowed", "premium_required") ->
        XmpSyncError.PLAN_REQUIRED
    else -> XmpSyncError.UNAVAILABLE
}

// ── Storage ─────────────────────────────────────────────────────────────────────────────────────────────────

interface XmpSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    suspend fun remove(profileId: Int, checkScope: () -> Unit)
}

@Singleton
class AndroidXmpSyncStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : XmpSyncStorage {
    override suspend fun load(profileId: Int): String? = factory.get(profileId, FEATURE).data.first()[SNAPSHOT]

    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            if (factory.isProfileDeleted(profileId)) throw CancellationException("XMPlayer profile removed")
            preferences[SNAPSHOT] = payload
        }
    }

    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            preferences.remove(SNAPSHOT)
        }
    }

    private companion object {
        const val FEATURE = "xmplayer_sync"
        val SNAPSHOT = stringPreferencesKey("snapshot")
    }
}

// ── The repository ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Keeps the snapshot of the connected XMPlayer profile.
 *  • FIRST LOAD in phases (history, favourites, lists), each saved as it lands, from the cursor read before the first
 *    phase — so a Free plan's one read a minute finishes over a few refreshes instead of failing, and nothing that
 *    changes meanwhile is missed (the changes after that cursor are replayed).
 *  • THEN only what changed: GET /v1/changes from the cursor (`more` → read again, `resync` → load again).
 *  • LIVE on Premium / Pro: /v1/stream (server-sent events) applies each change as it happens; the stream reconnects
 *    from the last cursor it saw.
 */
class XmpSyncRepository(
    private val storage: XmpSyncStorage,
    private val auth: XmpAuthStore,
    private val api: XmpApiClient,
    private val stream: XmpChangeStream,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(XmpSyncState())
    private val mutableProjection = MutableStateFlow(XmpProgressState())
    private var liveJob: Job? = null
    val state: StateFlow<XmpSyncState> = mutableState.asStateFlow()
    internal val progressState: StateFlow<XmpProgressState> = mutableProjection.asStateFlow()

    init {
        coroutineScope.launch {
            // Keyed on the profile and the connection only: saving the account details (/v1/me) must not restart this.
            combine(activeProfileId, auth.state) { profileId, authorization ->
                profileId to authorization.scope
            }.distinctUntilChanged().collectLatest {
                if (!matchesCurrent(mutableState.value.scope)) clearPublished()
                try {
                    ensureLoaded()
                    refresh(TrackingRefreshIntent.AUTOMATIC)
                    followLive()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(auth.scope())) mutableState.value = XmpSyncState(scope = auth.scope(), error = error.toXmpSyncError())
                }
            }
        }
    }

    fun currentScope(): XmpAuthScope = auth.scope().also(::checkScope)

    internal fun currentSnapshot(): XmpSyncSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): XmpProgressProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }?.projection ?: XmpProgressProjection.Empty

    /** True when the connected plan may write (Premium / Pro). */
    fun canWrite(): Boolean = auth.state.value.account?.write == true

    suspend fun ensureLoaded() = withContext(dispatcher) { mutex.withLock { loadCurrent() } }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        coroutineScope.launch {
            try {
                refresh(intent)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The state carries the error for the settings screen.
            }
        }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(dispatcher) {
        val scope = auth.scope()
        gate.runIfNeeded(scope.generation, shouldRun = {
            auth.state.value.isAuthenticated && matchesCurrent(scope) && shouldRefresh(intent)
        }) {
            mutex.withLock {
                checkScope(scope)
                val attemptedAt = now()
                try {
                    // Who the connection is comes first, so the snapshot is keyed to the right XMPlayer profile; its
                    // cursor starts a first load without a second read.
                    val cursorHint = if (auth.state.value.account == null) api.me(scope).cursor else null
                    var snapshot = loadCurrent() ?: return@withLock
                    mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                    snapshot = if (!snapshot.isInitialized) fullLoad(scope, snapshot, cursorHint) else readChanges(scope, snapshot)
                    commit(scope, snapshot.copy(checkedAtEpochMs = now()))
                    mutableState.value = mutableState.value.copy(isLoading = false, attemptedAtEpochMs = attemptedAt)
                } catch (error: CancellationException) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "refresh failed: ${error.javaClass.simpleName} ${error.message}")
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(
                        scope = scope,
                        isLoading = false,
                        error = error.toXmpSyncError(),
                        retryAtEpochMs = (error as? XmpApiException)?.retryAtEpochMs,
                        attemptedAtEpochMs = attemptedAt
                    )
                }
            }
        }
    }

    /** Apply a local change (a write Nuvio just made) at once; the next change read confirms or corrects it. */
    internal suspend fun applyLocal(scope: XmpAuthScope, change: (XmpSyncSnapshot) -> XmpSyncSnapshot) = withContext(dispatcher) {
        mutex.withLock {
            checkScope(scope)
            val previous = loadCurrent() ?: return@withLock
            val next = change(previous)
            if (next != previous) commit(scope, next)
        }
    }

    // ── first load ──

    private suspend fun fullLoad(scope: XmpAuthScope, start: XmpSyncSnapshot, cursorHint: Long? = null): XmpSyncSnapshot {
        var snapshot = start
        if (snapshot.loadCursor == null) {
            val me = if (cursorHint != null) null else api.me(scope)
            val profileId = me?.account?.profileId ?: auth.state.value.account?.profileId ?: throw XmpDecodingException()
            snapshot = XmpSyncSnapshot(xmpProfileId = profileId, loadCursor = cursorHint ?: me?.cursor ?: 0L)
            commit(scope, snapshot)
        }
        for (phase in XmpLoadPhase.entries) {
            if (phase in snapshot.loadedPhases) continue
            snapshot = when (phase) {
                XmpLoadPhase.HISTORY -> {
                    val body = api.decode<XmpHistoryResponse>(api.get("/v1/history", mapOf("limit" to "1000"), scope).body)
                    snapshot.copy(history = body.items.mapNotNull { it.toEntry() }.associateBy { it.key })
                }
                XmpLoadPhase.FAVORITES -> {
                    val body = api.decode<XmpFavoritesResponse>(api.get("/v1/favorites", scope = scope).body)
                    snapshot.copy(favorites = body.items.mapNotNull { it.toEntry() })
                }
                XmpLoadPhase.COLLECTIONS -> {
                    val body = api.decode<XmpCollectionsResponse>(api.get("/v1/collections", scope = scope).body)
                    snapshot.copy(collections = body.collections.map { it.toCollection() })
                }
            }.let { it.copy(loadedPhases = it.loadedPhases + phase) }
            commit(scope, snapshot)
        }
        val loaded = snapshot.copy(cursor = snapshot.loadCursor, loadCursor = null, loadedPhases = emptySet(), isInitialized = true)
        Log.i(TAG, "loaded: history=${loaded.history.size} favourites=${loaded.favorites.size} lists=${loaded.collections.size}")
        // What changed while the phases loaded.
        return readChanges(scope, loaded)
    }

    // ── changes ──

    private suspend fun readChanges(scope: XmpAuthScope, start: XmpSyncSnapshot): XmpSyncSnapshot {
        var snapshot = start
        repeat(MAX_CHANGE_PAGES) {
            val body = api.decode<XmpChangesResponse>(api.get("/v1/changes", mapOf("cursor" to (snapshot.cursor ?: 0L).toString()), scope).body)
            if (body.resync) {
                Log.i(TAG, "changes: resync — loading again")
                val fresh = XmpSyncSnapshot(xmpProfileId = snapshot.xmpProfileId)
                commit(scope, fresh)
                return fullLoad(scope, fresh)
            }
            snapshot = applyChanges(snapshot, body.changes).copy(cursor = body.cursor ?: snapshot.cursor)
            if (!body.more) return snapshot
        }
        return snapshot
    }

    /** One change as it arrives on the live stream. */
    internal suspend fun applyStreamed(scope: XmpAuthScope, change: XmpChange) = withContext(dispatcher) {
        mutex.withLock {
            if (!matchesCurrent(scope)) return@withLock
            val previous = loadCurrent() ?: return@withLock
            if (!previous.isInitialized) return@withLock
            val cursor = change.cursor
            if (cursor != null && previous.cursor != null && cursor <= previous.cursor) return@withLock
            commit(scope, applyChanges(previous, listOf(change)).copy(cursor = cursor ?: previous.cursor, checkedAtEpochMs = now()))
        }
    }

    // ── live ──

    private fun followLive() {
        liveJob?.cancel()
        val account = auth.state.value.account ?: return
        if (!account.stream || !auth.state.value.isAuthenticated) return
        val scope = auth.scope()
        liveJob = coroutineScope.launch {
            var backoff = 2_000L
            while (isActive && matchesCurrent(scope) && auth.state.value.isAuthenticated) {
                val cursor = currentSnapshot()?.takeIf { it.isInitialized }?.cursor
                if (cursor == null) { delay(5_000L); continue }
                try {
                    mutableState.value = mutableState.value.copy(isLive = true)
                    stream.follow(scope, cursor) { change -> applyStreamed(scope, change) }
                    backoff = 2_000L
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "live stream ended: ${error.javaClass.simpleName} ${error.message}")
                    if (error is XmpAuthException) break
                } finally {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLive = false)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
    }

    // ── persistence + publishing ──

    private suspend fun loadCurrent(): XmpSyncSnapshot? {
        val scope = auth.scope()
        checkScope(scope, requireAuthorization = false)
        val authorization = auth.state.value
        if (!authorization.isAuthenticated) {
            liveJob?.cancel()
            clearPublished()
            storage.remove(scope.profileId) { checkScope(scope, requireAuthorization = false) }
            return null
        }
        val xmpProfile = authorization.account?.profileId
        val current = mutableState.value
        if (current.scope == scope && current.snapshot != null && (xmpProfile == null || current.snapshot.xmpProfileId == xmpProfile)) {
            return current.snapshot
        }
        val persisted = storage.load(scope.profileId)
            ?.let { runCatching { json.decodeFromString<XmpSyncSnapshot>(it) }.getOrNull() }
            ?.takeIf { xmpProfile == null || it.xmpProfileId == xmpProfile }
            ?.withoutUnidentified()
        val snapshot = persisted ?: XmpSyncSnapshot(xmpProfileId = xmpProfile.orEmpty())
        checkScope(scope)
        mutableProjection.value = XmpProgressState(scope, XmpProgressProjection(snapshot))
        mutableState.value = XmpSyncState(scope, snapshot)
        return snapshot
    }

    private suspend fun commit(scope: XmpAuthScope, snapshot: XmpSyncSnapshot) {
        checkScope(scope)
        val previous = mutableState.value.snapshot
        storage.save(scope.profileId, json.encodeToString(snapshot)) { checkScope(scope) }
        checkScope(scope)
        val projectionInputsSame = previous != null && previous.history === snapshot.history &&
            previous.collections === snapshot.collections
        if (!projectionInputsSame) mutableProjection.value = XmpProgressState(scope, XmpProgressProjection(snapshot))
        mutableState.value = mutableState.value.copy(scope = scope, snapshot = snapshot)
    }

    private fun shouldRefresh(intent: TrackingRefreshIntent): Boolean {
        val current = mutableState.value.takeIf { matchesCurrent(it.scope) } ?: return true
        val time = now()
        if (current.retryAtEpochMs?.let { it > time } == true) return false
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        if (current.snapshot?.isInitialized != true) return true
        // Live: the stream keeps the snapshot current; poll only as a safety net.
        val interval = if (current.isLive) LIVE_SAFETY_INTERVAL_MS else automaticIntervalMs()
        if (current.error != null) return current.attemptedAtEpochMs?.let { time - it !in 0 until ERROR_RETRY_MS } ?: true
        return current.snapshot?.checkedAtEpochMs?.let { time - it !in 0 until interval } ?: true
    }

    /** Changes are one read: every minute on Free (its whole allowance), every 20 s on a paid plan without the stream. */
    private fun automaticIntervalMs(): Long = if ((auth.state.value.account?.readsPerMinute ?: 1) <= 1) 60_000L else 20_000L

    private fun checkScope(scope: XmpAuthScope, requireAuthorization: Boolean = true) {
        if (!matchesCurrent(scope)) throw CancellationException("XMPlayer account changed")
        if (requireAuthorization && !auth.state.value.isAuthenticated) throw XmpAuthException(XmpAuthError.AUTHORIZATION_REVOKED)
    }

    private fun matchesCurrent(scope: XmpAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        mutableProjection.value = XmpProgressState()
        mutableState.value = XmpSyncState()
    }

    companion object {
        private const val TAG = "XmpSync"
        private const val MAX_CHANGE_PAGES = 20
        const val ERROR_RETRY_MS = 60_000L
        const val LIVE_SAFETY_INTERVAL_MS = 15L * 60 * 1_000
    }
}

// ── reading API rows into the snapshot ──────────────────────────────────────────────────────────────────────

internal fun XmpHistoryRow.toEntry(): XmpHistoryEntry? {
    val it = item ?: return null
    val media = it.media() ?: return null
    val episode = it.type.equals("episode", ignoreCase = true)
    if (episode && (it.season == null || it.episode == null)) return null
    val p = (progress ?: 0.0).coerceIn(0.0, 1.0)
    return XmpHistoryEntry(
        key = key,
        media = media,
        season = if (episode) it.season else null,
        episode = if (episode) it.episode else null,
        progress = p,
        completed = completed == true || p >= XMP_COMPLETED,
        lastWatchedAt = lastWatchedAt ?: 0L
    )
}

internal fun XmpListRow.toEntry(): XmpListEntry? {
    val media = item?.media(poster) ?: return null
    return XmpListEntry(key = key, media = media, addedAt = addedAt ?: 0L)
}

internal fun XmpCollectionDto.toCollection(): XmpCollection =
    XmpCollection(id = id, kind = kind ?: "collection", name = name, type = type, items = items.mapNotNull { it.toEntry() })

/** XMPlayer's own rule: stopping at 95% or later is watched. */
internal const val XMP_COMPLETED = 0.95

/**
 * Apply changes from /v1/changes or /v1/stream. Unknown kinds (ratings, availability, schedules) are kept out: Nuvio has
 * nothing to show for them.
 */
internal fun applyChanges(snapshot: XmpSyncSnapshot, changes: List<XmpChange>): XmpSyncSnapshot {
    if (changes.isEmpty()) return snapshot
    var history = snapshot.history
    var favorites = snapshot.favorites
    var collections = snapshot.collections
    for (c in changes) {
        val key = c.key
        val kind = c.kind ?: continue
        when (kind) {
            "history" -> {
                if (key == null) continue
                if (c.removed == true) {
                    history = history - key
                    continue
                }
                val previous = history[key]
                val base = previous ?: XmpHistoryRow(key = key, item = c.item).toEntry() ?: continue
                val f = c.changes
                val progress = (f?.progress ?: base.progress).coerceIn(0.0, 1.0)
                history = history + (key to base.copy(
                    progress = progress,
                    completed = f?.completed ?: (progress >= XMP_COMPLETED),
                    lastWatchedAt = f?.lastWatchedAt ?: c.at ?: base.lastWatchedAt
                ))
            }
            "favorite" -> {
                if (key == null) continue
                favorites = favorites.filterNot { it.key == key }
                if (c.favorite == true) {
                    XmpListRow(key = key, item = c.item, addedAt = c.at).toEntry()?.let { favorites = listOf(it) + favorites }
                }
            }
            "watchlist", "dropped", "hidden", "collection" -> {
                val id = c.collection ?: continue
                val existing = collections.firstOrNull { it.id == id }
                when {
                    c.deleted == true -> collections = collections.filterNot { it.id == id }
                    c.member != null && key != null -> {
                        val list = existing ?: XmpCollection(
                            id = id,
                            kind = kind,
                            type = if (c.item?.type.equals("movie", ignoreCase = true)) "movies" else "series"
                        )
                        val without = list.items.filterNot { it.key == key }
                        val items = if (c.member == true) {
                            XmpListRow(key = key, item = c.item, addedAt = c.at).toEntry()?.let { without + it } ?: without
                        } else without
                        collections = collections.filterNot { it.id == id } + list.copy(items = items)
                    }
                    else -> {
                        val list = existing ?: XmpCollection(id = id, kind = kind)
                        collections = collections.filterNot { it.id == id } +
                            list.copy(name = c.name ?: list.name, type = c.type ?: list.type)
                    }
                }
            }
        }
    }
    return snapshot.copy(history = history, favorites = favorites, collections = collections)
}
