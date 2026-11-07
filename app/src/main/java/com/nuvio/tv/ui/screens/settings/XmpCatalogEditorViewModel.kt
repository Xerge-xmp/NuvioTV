package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.xmplayer.LibraryId
import com.nuvio.tv.data.mediaserver.xmplayer.XmpServerProvider
import com.nuvio.tv.data.xmplayer.XmpApiException
import com.nuvio.tv.data.xmplayer.XmpAuthException
import com.nuvio.tv.data.xmplayer.XmpCatalogCategory
import com.nuvio.tv.data.xmplayer.XmpCatalogClient
import com.nuvio.tv.data.xmplayer.XmpCatalogEdit
import com.nuvio.tv.data.xmplayer.XmpCatalogKind
import com.nuvio.tv.data.xmplayer.XmpCatalogTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why the catalog cannot be read or edited right now — each has its own message on the screen. */
enum class XmpCatalogProblem {
    /** The account's XMPlayer DVR agent is not running. */
    AGENT_OFFLINE,
    /** The agent is connected but did not answer within the Sync API's wait: busy or stuck. */
    AGENT_NOT_ANSWERING,
    /** The account has no XMPlayer DVR agent. */
    NO_AGENT,
    /** Editing playlists is XMPlayer Premium and Pro. */
    PREMIUM,
    /** The XMPlayer connection was made before Nuvio asked for catalog access: connect again. */
    PERMISSION,
    /** Nuvio is not connected to XMPlayer (Settings ▸ Tracking). */
    NOT_CONNECTED,
    FAILED
}

data class XmpCatalogSourceUi(val id: String, val name: String)

data class XmpCatalogTab(val sourceId: String, val kind: XmpCatalogKind)

data class XmpCatalogTitlesUi(
    val tab: XmpCatalogTab,
    val category: XmpCatalogCategory,
    val titles: List<XmpCatalogTitle> = emptyList(),
    val nextPage: Int = 1,
    val hasMore: Boolean = true,
    val loading: Boolean = false
)

data class XmpCatalogEditorState(
    val loading: Boolean = true,
    val problem: XmpCatalogProblem? = null,
    val sources: List<XmpCatalogSourceUi> = emptyList(),
    val open: XmpCatalogTab? = null,
    val categories: List<XmpCatalogCategory> = emptyList(),
    val categoriesLoading: Boolean = false,
    val busy: Boolean = false,
    /** What XMPlayer did with the last edit ("hid 1 category"), or why it could not. */
    val lastResult: String? = null,
    val titles: XmpCatalogTitlesUi? = null
)

/**
 * Edits the connected XMPlayer profile's IPTV catalog from Nuvio. Every edit is applied by the account's XMPlayer DVR
 * agent with XMPlayer's own Source Editor, so every XMPlayer device follows; afterwards Nuvio re-reads what it airs.
 */
@HiltViewModel
class XmpCatalogEditorViewModel @Inject constructor(
    private val catalog: XmpCatalogClient,
    private val provider: XmpServerProvider,
    private val servers: ServerRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(XmpCatalogEditorState())
    val state: StateFlow<XmpCatalogEditorState> = mutable.asStateFlow()
    private var connectionId: String? = null
    private var titlesJob: Job? = null
    /** Edits run one after another, in the order they were made — never dropped while another is in flight. */
    private val edits = Mutex()
    /** One refresh of what Nuvio airs after a burst of edits (a newer edit restarts it). */
    private var airedJob: Job? = null

    fun start(connectionId: String) {
        if (this.connectionId == connectionId && !mutable.value.loading) return
        this.connectionId = connectionId
        reload()
    }

    fun reload() {
        // the problem stays on screen while it is checked again: its row keeps the remote's focus (a row that vanished
        // under it sent focus to the side menu — measured on the TV 2026-11-06)
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sources = sourcesOnceReady().map { XmpCatalogSourceUi(it.id, it.name) }
                mutable.update { it.copy(loading = false, sources = sources, problem = null) }
                mutable.value.open?.let { loadCategories(it) }
                // XMPlayer was added while its DVR agent was away (only the recordings were aired): the agent answers
                // now, so Nuvio reads what it airs — the categories reach Home without another step.
                val id = connectionId
                if (sources.isNotEmpty() && id != null &&
                    servers.connection(id)?.libraries?.none { LibraryId.parse(it.id) is LibraryId.Category } == true
                ) {
                    runCatching { servers.refreshLibraries(id) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(loading = false, problem = error.problem()) }
            }
        }
    }

    /**
     * The playlists — waited for while the agent is still starting: a DVR agent that has just come online answers
     * "agent_not_ready" for the few seconds it reads the account's playlists, and that is not "offline".
     */
    private suspend fun sourcesOnceReady(): List<com.nuvio.tv.data.xmplayer.XmpCatalogSource> {
        var tries = 0
        while (true) {
            try {
                return catalog.sources()
            } catch (error: XmpApiException) {
                if (error.code != "agent_not_ready" || ++tries > NOT_READY_TRIES) throw error
                delay(NOT_READY_WAIT_MS)
            }
        }
    }

    /** Opens (or closes, when it is open) one playlist's movies or series. */
    fun toggleTab(tab: XmpCatalogTab) {
        if (mutable.value.open == tab) {
            mutable.update { it.copy(open = null, categories = emptyList()) }
            return
        }
        mutable.update { it.copy(open = tab, categories = emptyList(), categoriesLoading = true, lastResult = null) }
        viewModelScope.launch(Dispatchers.IO) { loadCategories(tab) }
    }

    private suspend fun loadCategories(tab: XmpCatalogTab) {
        try {
            val categories = catalog.categories(tab.sourceId, tab.kind)
            mutable.update { if (it.open == tab) it.copy(categories = categories, categoriesLoading = false, problem = null) else it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.update { it.copy(categoriesLoading = false, problem = error.problem()) }
        }
    }

    fun setHidden(category: XmpCatalogCategory, hidden: Boolean) =
        apply(XmpCatalogEdit.HideCategories(listOf(category.id), hidden))

    fun setFavorite(category: XmpCatalogCategory, on: Boolean) =
        apply(XmpCatalogEdit.FavoriteCategories(listOf(category.id), on))

    fun rename(category: XmpCatalogCategory, name: String) =
        apply(XmpCatalogEdit.RenameCategory(category.id, name.trim()))

    /** Moves a category one place up (-1) or down (+1) in XMPlayer's order. */
    fun move(category: XmpCatalogCategory, by: Int) {
        val ids = mutable.value.categories.map { it.id }.toMutableList()
        val from = ids.indexOf(category.id)
        val to = from + by
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        apply(XmpCatalogEdit.ReorderCategories(ids))
    }

    private fun apply(edit: XmpCatalogEdit) {
        val tab = mutable.value.open ?: return
        mutable.update { it.copy(busy = true, lastResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val did = edits.withLock { catalog.edit(tab.sourceId, tab.kind, edit) }
                // The row shows the edit as soon as XMPlayer has made it; the re-read below only confirms it.
                mutable.update { state ->
                    state.copy(lastResult = did, categories = if (state.open == tab) state.categories.edited(edit) else state.categories)
                }
                mutable.update { it.copy(busy = edits.isLocked) }
                loadCategories(tab)
                refreshAiredSoon()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(problem = error.problem()) }
            } finally {
                mutable.update { it.copy(busy = edits.isLocked) }
            }
        }
    }

    private fun List<XmpCatalogCategory>.edited(edit: XmpCatalogEdit): List<XmpCatalogCategory> = when (edit) {
        is XmpCatalogEdit.HideCategories -> map { if (it.id in edit.ids) it.copy(hidden = edit.hidden) else it }
        is XmpCatalogEdit.FavoriteCategories -> map { if (it.id in edit.ids) it.copy(favorite = edit.on) else it }
        is XmpCatalogEdit.RenameCategory -> map {
            if (it.id != edit.id) it
            else {
                val original = it.providerName ?: it.name
                it.copy(name = edit.name.ifEmpty { original }, renamed = edit.name.isNotEmpty() && edit.name != original)
            }
        }
        is XmpCatalogEdit.ReorderCategories -> {
            val byId = associateBy { it.id }
            edit.order.mapNotNull(byId::get) + filterNot { it.id in edit.order }
        }
        else -> this
    }

    // ── titles of one category ───────────────────────────────────────────────────────────────────────────────

    fun openTitles(category: XmpCatalogCategory) {
        val tab = mutable.value.open ?: return
        mutable.update { it.copy(titles = XmpCatalogTitlesUi(tab, category)) }
        loadMoreTitles()
    }

    fun closeTitles() {
        titlesJob?.cancel()
        mutable.update { it.copy(titles = null) }
    }

    fun loadMoreTitles() {
        val current = mutable.value.titles ?: return
        if (current.loading || !current.hasMore) return
        mutable.update { it.copy(titles = current.copy(loading = true)) }
        titlesJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = catalog.titles(current.tab.sourceId, current.tab.kind, current.category.id, current.nextPage, TITLES_PAGE)
                mutable.update { state ->
                    val titles = state.titles?.takeIf { it.category.id == current.category.id } ?: return@update state
                    state.copy(titles = titles.copy(titles = titles.titles + page, nextPage = titles.nextPage + 1, hasMore = page.size >= TITLES_PAGE, loading = false))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(problem = error.problem(), titles = it.titles?.copy(loading = false, hasMore = false)) }
            }
        }
    }

    fun setTitleHidden(title: XmpCatalogTitle, hidden: Boolean) =
        applyToTitle(title, XmpCatalogEdit.HideTitles(listOf(title.id), hidden)) { it.copy(hidden = hidden) }

    fun renameTitle(title: XmpCatalogTitle, name: String) {
        val original = title.providerName ?: title.name
        applyToTitle(title, XmpCatalogEdit.RenameTitle(title.id, name.trim(), original)) {
            it.copy(name = name.trim().ifEmpty { original }, renamed = name.isNotBlank() && name.trim() != original)
        }
    }

    private fun applyToTitle(title: XmpCatalogTitle, edit: XmpCatalogEdit, change: (XmpCatalogTitle) -> XmpCatalogTitle) {
        val titles = mutable.value.titles ?: return
        mutable.update { it.copy(busy = true, lastResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val did = edits.withLock { catalog.edit(titles.tab.sourceId, titles.tab.kind, edit) }
                mutable.update { state ->
                    state.copy(
                        lastResult = did,
                        titles = state.titles?.let { list -> list.copy(titles = list.titles.map { if (it.id == title.id) change(it) else it }) }
                    )
                }
                refreshAiredSoon()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { it.copy(problem = error.problem()) }
            } finally {
                mutable.update { it.copy(busy = edits.isLocked) }
            }
        }
    }

    /**
     * Nuvio re-reads which categories it airs and forgets the aired pages, so Home follows the edits. Re-reading every
     * playlist goes through the DVR agent and takes a while, so it runs once after a burst of edits and never holds
     * up the next edit.
     */
    private fun refreshAiredSoon() {
        provider.clearAired()
        val id = connectionId ?: return
        airedJob?.cancel()
        airedJob = viewModelScope.launch(Dispatchers.IO) {
            delay(AIRED_REFRESH_DELAY_MS)
            runCatching { servers.refreshLibraries(id) }
        }
    }

    private fun Exception.problem(): XmpCatalogProblem = when (this) {
        is XmpAuthException -> XmpCatalogProblem.NOT_CONNECTED
        is XmpApiException -> when (code) {
            "no_answering_device", "agent_offline" -> XmpCatalogProblem.AGENT_OFFLINE
            "agent_timeout" -> XmpCatalogProblem.AGENT_NOT_ANSWERING
            "no_agent" -> XmpCatalogProblem.NO_AGENT
            "premium_required" -> XmpCatalogProblem.PREMIUM
            "scope_required" -> XmpCatalogProblem.PERMISSION
            else -> if (status == 503) XmpCatalogProblem.AGENT_OFFLINE else XmpCatalogProblem.FAILED
        }
        else -> XmpCatalogProblem.FAILED
    }

    private companion object {
        const val TITLES_PAGE = 100
        const val AIRED_REFRESH_DELAY_MS = 1_500L
        const val NOT_READY_WAIT_MS = 3_000L
        const val NOT_READY_TRIES = 10
    }
}
