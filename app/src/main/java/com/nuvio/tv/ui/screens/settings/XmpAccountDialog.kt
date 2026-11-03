package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.util.rememberRawSvgPainter

@Composable
internal fun XmpAccountDialog(
    state: XmpTrackerUiState,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val logo = rememberRawSvgPainter(R.raw.xmplayer_logo, 150.dp)
    if (state.isConnected) {
        val who = state.profileName ?: stringResource(R.string.xmplayer_account_fallback)
        ConnectedTrackingAccountDialog(TrackingDialogBrand.XMPLAYER, logo, onDismiss) {
            ConnectedTrackingAccountContent(
                brand = TrackingDialogBrand.XMPLAYER,
                logo = logo,
                logoContentDescription = stringResource(R.string.xmplayer_name),
                connectedLabel = state.planLabel?.let { stringResource(R.string.xmplayer_connected_as_plan, who, it) }
                    ?: stringResource(R.string.xmplayer_connected_as, who),
                connectedDescription = if (state.canWrite) stringResource(R.string.xmplayer_tracking_description)
                    else stringResource(R.string.xmplayer_read_only_description),
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading || state.isSyncing,
                onDisconnect = onDisconnect,
                onSync = onSync
            )
        }
    } else {
        NuvioDialog(onDismiss = onDismiss, title = "", width = 720.dp, titleTextAlign = TextAlign.Center, suppressFirstKeyUp = false) {
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.xmplayer_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.xmplayer_name),
                logoLabel = stringResource(R.string.xmplayer_name),
                qrContentDescription = stringResource(R.string.xmplayer_qr_description),
                instruction = stringResource(R.string.xmplayer_awaiting_instruction),
                userCode = state.session?.userCode,
                displayUrl = state.session?.verificationUri,
                qrUrl = state.session?.verificationUriComplete,
                expiresAtEpochMs = state.session?.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.xmplayer_missing_client),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}
