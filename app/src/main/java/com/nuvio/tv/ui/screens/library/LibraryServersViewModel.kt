package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerLibraryRef
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.domain.model.CatalogRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One library of a connected server, as a shelf of the Library's Servers tab. [loading]: its row is being read (a row it
 * already had stays shown meanwhile); [failure]: the last read failed.
 */
data class LibraryServerShelf(
    val key: String,
    val title: String,
    val connectionId: String,
    val connectionName: String,
    val providerId: String,
    val row: CatalogRow? = null,
    val failure: ServerFailure? = null,
    val loading: Boolean = false
)

@HiltViewModel
class LibraryServersViewModel @Inject constructor(
    private val serverCatalog: ServerCatalog,
    repository: ServerRepository
) : ViewModel() {
    val servers: StateFlow<ServersUiState> = repository.uiState
    private val _shelves = MutableStateFlow<List<LibraryServerShelf>?>(null)
    val shelves: StateFlow<List<LibraryServerShelf>?> = _shelves.asStateFlow()
    private var loadJob: Job? = null
    private var loadedRevision: Int? = null
    var focusedShelfKey: String? = null
        private set
    val focusedIndexes = mutableMapOf<String, Int>()

    fun onItemFocused(shelfKey: String, index: Int) {
        focusedShelfKey = shelfKey
        focusedIndexes[shelfKey] = index
    }

    /**
     * Reads the shelves, each one published as soon as its row arrives (a server with many libraries — XMPlayer airs
     * every category — fills the tab row by row instead of after the last one). Opening the tab again reads what failed
     * or never finished; the rows that loaded stay until the servers change.
     */
    fun load() {
        val revision = servers.value.revision
        val current = _shelves.value
        val sameServers = loadedRevision == revision && current != null
        if (current != null && sameServers && current.none { it.loading || it.failure != null }) return
        loadedRevision = revision
        loadJob?.cancel()
        val previous = current.orEmpty().associateBy { it.key }
        val refs = serverCatalog.libraries().associateBy(::keyOf)
        val shelves = refs.map { (key, ref) ->
            val before = previous[key]
            if (sameServers && before != null && before.row != null && before.failure == null && !before.loading) before
            else LibraryServerShelf(
                key = key,
                title = ref.title,
                connectionId = ref.connection.id,
                connectionName = ref.connection.name,
                providerId = ref.connection.providerId,
                row = before?.row,
                loading = true
            )
        }
        _shelves.value = shelves
        loadJob = viewModelScope.launch {
            shelves.filter { it.loading }.forEach { pending ->
                val ref = refs[pending.key] ?: return@forEach
                launch {
                    val done = shelf(pending, ref)
                    _shelves.update { list -> list?.map { if (it.key == done.key) done else it } }
                }
            }
        }
    }

    private fun keyOf(ref: ServerLibraryRef): String = "${ref.connection.id}:${ref.library.id}"

    private suspend fun shelf(pending: LibraryServerShelf, ref: ServerLibraryRef): LibraryServerShelf = try {
        pending.copy(row = serverCatalog.row(ref.connection.id, ref.library.id), failure = null, loading = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // A row it already showed stays (its titles did not go away because the server cannot answer now); the
        // failure is kept so the next visit reads it again.
        pending.copy(failure = error.serverFailure(), loading = false)
    }
}
