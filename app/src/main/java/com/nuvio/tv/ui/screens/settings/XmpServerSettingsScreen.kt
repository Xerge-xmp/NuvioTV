@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.xmplayer.XmpCatalogCategory
import com.nuvio.tv.data.xmplayer.XmpCatalogKind
import com.nuvio.tv.data.xmplayer.XmpCatalogTitle
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The XMPlayer source's settings: what Nuvio airs from the connected XMPlayer profile, and the editor for its IPTV
 * catalog. Built for the remote: a playlist's Movies or Series opens in place; OK on a category opens its actions
 * (show / hide first); a category's titles open in a dialog where OK on a title opens its actions.
 */
@Composable
internal fun XmpServerSettingsContent(
    connection: ServerConnection,
    statusLine: String,
    initialFocusRequester: FocusRequester?,
    serversViewModel: MediaServersViewModel,
    viewModel: XmpCatalogEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(connection.id) { viewModel.start(connection.id) }
    var selectedCategory by remember { mutableStateOf<XmpCatalogCategory?>(null) }
    var renamingCategory by remember { mutableStateOf<XmpCatalogCategory?>(null) }
    var selectedTitle by remember { mutableStateOf<XmpCatalogTitle?>(null) }
    var renamingTitle by remember { mutableStateOf<XmpCatalogTitle?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    // On a remote, closing an action dialog must put focus back on the row it was opened from.
    var returnToCategory by remember { mutableStateOf<String?>(null) }
    var returnToTitle by remember { mutableStateOf<String?>(null) }
    val dialogOpen = selectedCategory != null || renamingCategory != null || state.titles != null
    // Retry worked, so its row went away under the remote's focus: focus goes to the top of the page, not the side menu.
    var retried by remember { mutableStateOf(false) }
    LaunchedEffect(state.problem) {
        if (state.problem == null && retried) {
            retried = false
            delay(80)
            runCatching { initialFocusRequester?.requestFocus() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(title = connection.name, subtitle = statusLine)
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "xmp_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.servers_enabled),
                            subtitle = stringResource(R.string.xmp_server_enabled_description),
                            checked = connection.enabled,
                            onToggle = { serversViewModel.setEnabled(connection.id, !connection.enabled) },
                            modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                        )
                    }
                    state.problem?.let { problem ->
                        item(key = "xmp_problem") {
                            SettingsActionRow(
                                title = stringResource(problem.titleRes()),
                                subtitle = stringResource(problem.messageRes()),
                                value = stringResource(if (state.loading) R.string.xmp_server_loading else R.string.xmp_server_retry),
                                leadingIcon = Icons.Default.Sync,
                                onClick = { if (!state.loading) { retried = true; viewModel.reload() } }
                            )
                        }
                    }
                    state.lastResult?.takeIf { it.isNotBlank() }?.let { result ->
                        item(key = "xmp_result") { XmpNote(stringResource(R.string.xmp_server_done, result)) }
                    }
                    item(key = "xmp_playlists_label") {
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                            ServerSectionLabel(stringResource(R.string.xmp_server_playlists))
                            XmpNote(
                                stringResource(
                                    when {
                                        state.loading -> R.string.xmp_server_loading
                                        state.sources.isEmpty() && state.problem == null -> R.string.xmp_server_no_playlists
                                        else -> R.string.xmp_server_playlists_description
                                    }
                                )
                            )
                        }
                    }
                    state.sources.forEach { source ->
                        XmpCatalogKind.entries.forEach { kind ->
                            val tab = XmpCatalogTab(source.id, kind)
                            val open = state.open == tab
                            item(key = "xmp_tab_${source.id}_${kind.wire}") {
                                val hidden = state.categories.count { it.hidden }
                                SettingsActionRow(
                                    title = stringResource(R.string.xmp_server_tab, source.name, stringResource(kind.labelRes())),
                                    subtitle = when {
                                        !open -> stringResource(R.string.xmp_server_tab_closed)
                                        state.categoriesLoading -> stringResource(R.string.xmp_server_loading)
                                        else -> stringResource(R.string.xmp_server_tab_open, state.categories.size, hidden)
                                    },
                                    value = if (open) "▾" else "▸",
                                    leadingIcon = Icons.Default.VideoLibrary,
                                    onClick = { viewModel.toggleTab(tab) }
                                )
                            }
                            if (open) {
                                items(state.categories, key = { "xmp_cat_${source.id}_${kind.wire}_${it.id}" }) { category ->
                                    val rowFocus = remember(category.id) { FocusRequester() }
                                    // Rows stay focusable during an edit (the view model ignores a second one), so focus can come back.
                                    LaunchedEffect(returnToCategory, dialogOpen, state.categoriesLoading, state.busy) {
                                        if (returnToCategory == category.id && !dialogOpen && !state.categoriesLoading && !state.busy) {
                                            delay(80)
                                            runCatching { rowFocus.requestFocus() }
                                            returnToCategory = null
                                        }
                                    }
                                    SettingsActionRow(
                                        title = category.name,
                                        subtitle = category.subtitle(),
                                        value = stringResource(if (category.hidden) R.string.xmp_server_hidden else R.string.xmp_server_shown),
                                        titleTrailingIcon = if (category.favorite) Icons.Default.Star else null,
                                        valueColor = if (category.hidden) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextSecondary,
                                        onClick = { selectedCategory = category; returnToCategory = category.id },
                                        modifier = Modifier.padding(start = NuvioTheme.spacing.lg).focusRequester(rowFocus)
                                    )
                                }
                            }
                        }
                    }
                    item(key = "xmp_refresh") {
                        SettingsActionRow(
                            title = stringResource(R.string.xmp_server_refresh),
                            subtitle = stringResource(R.string.xmp_server_refresh_description),
                            leadingIcon = Icons.Default.Sync,
                            onClick = {
                                viewModel.reload()
                                serversViewModel.refreshLibraries(connection.id) {}
                            }
                        )
                    }
                    item(key = "xmp_remove") {
                        SettingsActionRow(
                            title = stringResource(R.string.servers_remove),
                            subtitle = null,
                            leadingIcon = Icons.Default.Delete,
                            onClick = { confirmRemove = true }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    selectedCategory?.let { category ->
        val position = state.categories.indexOfFirst { it.id == category.id }
        NuvioDialog(
            onDismiss = { selectedCategory = null },
            title = category.name,
            subtitle = category.subtitle()
        ) {
            XmpDialogAction(
                text = stringResource(if (category.hidden) R.string.xmp_server_show else R.string.xmp_server_hide),
                primary = true
            ) { viewModel.setHidden(category, !category.hidden); selectedCategory = null }
            XmpDialogAction(stringResource(if (category.favorite) R.string.xmp_server_unfavorite else R.string.xmp_server_favorite)) {
                viewModel.setFavorite(category, !category.favorite); selectedCategory = null
            }
            XmpDialogAction(stringResource(R.string.xmp_server_rename)) { renamingCategory = category; selectedCategory = null }
            if (position > 0) XmpDialogAction(stringResource(R.string.xmp_server_move_up)) { viewModel.move(category, -1); selectedCategory = null }
            if (position in 0 until state.categories.lastIndex) {
                XmpDialogAction(stringResource(R.string.xmp_server_move_down)) { viewModel.move(category, 1); selectedCategory = null }
            }
            XmpDialogAction(stringResource(R.string.xmp_server_titles)) { viewModel.openTitles(category); selectedCategory = null }
        }
    }

    renamingCategory?.let { category ->
        XmpRenameDialog(
            title = stringResource(R.string.xmp_server_rename_category),
            subtitle = category.providerName?.let { stringResource(R.string.xmp_server_rename_hint, it) },
            initial = category.name,
            onSave = { viewModel.rename(category, it); renamingCategory = null },
            onDismiss = { renamingCategory = null }
        )
    }

    val titles = state.titles
    if (titles != null && selectedTitle == null && renamingTitle == null) {
        NuvioDialog(
            onDismiss = viewModel::closeTitles,
            title = titles.category.name,
            subtitle = stringResource(R.string.xmp_server_titles_description),
            width = 760.dp
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(titles.titles, key = { "xmp_title_${it.id}" }) { title ->
                    val rowFocus = remember(title.id) { FocusRequester() }
                    LaunchedEffect(returnToTitle) {
                        if (returnToTitle == title.id) {
                            delay(80)
                            runCatching { rowFocus.requestFocus() }
                            returnToTitle = null
                        }
                    }
                    SettingsActionRow(
                        title = title.name,
                        subtitle = title.subtitle(),
                        value = stringResource(if (title.hidden) R.string.xmp_server_hidden else R.string.xmp_server_shown),
                        valueColor = if (title.hidden) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextSecondary,
                        onClick = { selectedTitle = title; returnToTitle = title.id },
                        modifier = Modifier.focusRequester(rowFocus)
                    )
                }
                if (titles.hasMore || titles.loading) {
                    item(key = "xmp_titles_more") {
                        SettingsActionRow(
                            title = stringResource(if (titles.loading) R.string.xmp_server_loading else R.string.xmp_server_more),
                            subtitle = null,
                            enabled = !titles.loading,
                            onClick = viewModel::loadMoreTitles,
                            onFocused = { if (!titles.loading) viewModel.loadMoreTitles() }
                        )
                    }
                }
                if (!titles.loading && !titles.hasMore && titles.titles.isEmpty()) {
                    item(key = "xmp_titles_empty") { XmpNote(stringResource(R.string.xmp_server_titles_empty)) }
                }
            }
        }
    }

    selectedTitle?.let { title ->
        NuvioDialog(onDismiss = { selectedTitle = null }, title = title.name, subtitle = title.subtitle()) {
            XmpDialogAction(stringResource(if (title.hidden) R.string.xmp_server_show else R.string.xmp_server_hide), primary = true) {
                viewModel.setTitleHidden(title, !title.hidden); selectedTitle = null
            }
            XmpDialogAction(stringResource(R.string.xmp_server_rename)) { renamingTitle = title; selectedTitle = null }
        }
    }

    renamingTitle?.let { title ->
        XmpRenameDialog(
            title = stringResource(R.string.xmp_server_rename_title),
            subtitle = title.providerName?.let { stringResource(R.string.xmp_server_rename_hint, it) },
            initial = title.name,
            onSave = { viewModel.renameTitle(title, it); renamingTitle = null },
            onDismiss = { renamingTitle = null }
        )
    }

    if (confirmRemove) {
        NuvioDialog(
            onDismiss = { confirmRemove = false },
            title = stringResource(R.string.servers_remove_confirm_title),
            // Not the generic "signs you out" text: removing this server keeps Nuvio's XMPlayer connection (sync, tracking).
            subtitle = stringResource(R.string.xmp_server_remove_confirm_message)
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = { confirmRemove = false })
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_remove),
                    onClick = { confirmRemove = false; serversViewModel.remove(connection.id) },
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun XmpNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
    )
}

/** One full-width action in a dialog: on a remote, actions read top to bottom and the first takes focus. */
@Composable
private fun XmpDialogAction(text: String, primary: Boolean = false, onClick: () -> Unit) {
    SettingsActionRow(
        title = text,
        subtitle = null,
        onClick = onClick,
        valueColor = if (primary) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary
    )
}

@Composable
private fun XmpCatalogCategory.subtitle(): String = listOfNotNull(
    count?.let { stringResource(R.string.xmp_server_titles_count, it) },
    matched?.takeIf { count != null }?.let { stringResource(R.string.xmp_server_matched_count, it) },
    providerName?.takeIf { renamed }?.let { stringResource(R.string.xmp_server_renamed_from, it) },
    stringResource(R.string.xmp_server_custom).takeIf { custom },
    stringResource(R.string.xmp_server_favorite_note).takeIf { favorite }
).joinToString(" · ")

@Composable
private fun XmpCatalogTitle.subtitle(): String = listOfNotNull(
    year,
    providerName?.takeIf { renamed }?.let { stringResource(R.string.xmp_server_renamed_from, it) },
    stringResource(if (isMatched) R.string.xmp_server_matched else R.string.xmp_server_unmatched)
).joinToString(" · ")

private fun XmpCatalogKind.labelRes(): Int = if (this == XmpCatalogKind.MOVIES) R.string.type_movies else R.string.type_series_plural

private fun XmpCatalogProblem.titleRes(): Int = when (this) {
    XmpCatalogProblem.AGENT_OFFLINE -> R.string.xmp_server_problem_offline
    XmpCatalogProblem.NO_AGENT -> R.string.xmp_server_problem_no_agent
    XmpCatalogProblem.PREMIUM -> R.string.xmp_server_problem_premium
    XmpCatalogProblem.PERMISSION -> R.string.xmp_server_problem_permission
    XmpCatalogProblem.NOT_CONNECTED -> R.string.xmp_server_problem_not_connected
    XmpCatalogProblem.FAILED -> R.string.xmp_server_problem_failed
}

private fun XmpCatalogProblem.messageRes(): Int = when (this) {
    XmpCatalogProblem.AGENT_OFFLINE -> R.string.xmp_server_problem_offline_message
    XmpCatalogProblem.NO_AGENT -> R.string.xmp_server_problem_no_agent_message
    XmpCatalogProblem.PREMIUM -> R.string.xmp_server_problem_premium_message
    XmpCatalogProblem.PERMISSION -> R.string.xmp_server_problem_permission_message
    XmpCatalogProblem.NOT_CONNECTED -> R.string.xmp_server_problem_not_connected_message
    XmpCatalogProblem.FAILED -> R.string.xmp_server_problem_failed_message
}

/** A one-line name editor: the text field takes the remote's OK to open the keyboard; Save keeps it. */
@Composable
private fun XmpRenameDialog(title: String, subtitle: String?, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = subtitle, width = 700.dp) {
        Card(
            onClick = { inputFocus.requestFocus() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = RoundedCornerShape(10.dp)),
                focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(10.dp))
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocus)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(text = stringResource(R.string.xmp_server_restore_name), onClick = { onSave("") })
            SettingsDialogActionButton(text = stringResource(R.string.action_save), onClick = { onSave(value) }, primary = true)
        }
    }
}
