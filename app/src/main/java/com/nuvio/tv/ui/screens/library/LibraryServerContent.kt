package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.xmplayer.XmpServerProvider
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle

internal fun LazyGridScope.libraryServerContent(
    shelves: List<LibraryServerShelf>?,
    posterCardStyle: PosterCardStyle,
    rowInset: Dp,
    focusTarget: Pair<String, Int>?,
    focusedIndexes: Map<String, Int>,
    onItemFocused: (String, Int) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: (CatalogRow) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    val shown = shelves?.filter { it.row?.items?.isNotEmpty() == true }.orEmpty()
    when {
        shelves == null || (shown.isEmpty() && shelves.any { it.loading }) -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }

        shelves.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_servers_empty_title),
                subtitle = stringResource(R.string.library_servers_empty_message),
                icon = Icons.Default.Dns,
                height = 260.dp
            )
        }

        else -> {
            // Libraries of one server that failed for one reason read as ONE message: when XMPlayer's DVR agent is away,
            // every category fails the same way, and that is one fact, not a screen of identical errors.
            val failedTogether = shelves.filter { it.row?.items.isNullOrEmpty() && it.failure != null }
                .groupBy { it.connectionId to it.failure }
            val told = mutableSetOf<Pair<String, ServerFailure?>>()
            shelves.forEach { shelf ->
                val row = shelf.row
                val failure = shelf.failure
                if (row != null && row.items.isNotEmpty()) {
                    item(key = "server_shelf_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                        CatalogRowSection(
                            catalogRow = row,
                            onItemClick = onItemClick,
                            onSeeAll = { onSeeAll(row) },
                            showSeeAll = row.hasMore,
                            posterCardStyle = posterCardStyle,
                            showAddonName = false,
                            showCatalogTypeSuffix = false,
                            isItemWatched = isWatched,
                            onItemLongPress = onItemLongPress,
                            modifier = Modifier.bleed(rowInset),
                            focusedItemIndex = focusTarget?.takeIf { it.first == shelf.key }?.second ?: -1,
                            restorerFocusedIndex = focusedIndexes[shelf.key] ?: -1,
                            onItemFocused = { index -> onItemFocused(shelf.key, index) }
                        )
                    }
                } else if (failure != null && told.add(shelf.connectionId to failure)) {
                    val many = (failedTogether[shelf.connectionId to failure]?.size ?: 1) > 1
                    item(key = "server_shelf_error_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyScreenState(
                            title = stringResource(R.string.library_server_load_failed, if (many) shelf.connectionName else shelf.title),
                            subtitle = stringResource(shelf.failureMessageRes(failure)),
                            icon = Icons.Default.Dns,
                            height = 180.dp
                        )
                    }
                }
            }
            if (shelves.any { it.loading }) {
                item(key = "server_shelves_loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
            }
        }
    }
}

/** XMPlayer's libraries come through the account's DVR agent: "unreachable" there means the agent, not a server. */
private fun LibraryServerShelf.failureMessageRes(failure: ServerFailure): Int =
    if (providerId == XmpServerProvider.PROVIDER_ID && failure == ServerFailure.UNREACHABLE) R.string.xmp_library_agent_away
    else failure.messageRes()

private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
