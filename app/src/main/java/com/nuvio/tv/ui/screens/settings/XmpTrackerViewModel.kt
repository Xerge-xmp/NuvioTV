package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.xmplayer.XmpAuthError
import com.nuvio.tv.data.xmplayer.XmpAuthException
import com.nuvio.tv.data.xmplayer.XmpAuthRepository
import com.nuvio.tv.data.xmplayer.XmpAuthScope
import com.nuvio.tv.data.xmplayer.XmpAuthStore
import com.nuvio.tv.data.xmplayer.XmpDevicePollResult
import com.nuvio.tv.data.xmplayer.XmpDeviceSession
import com.nuvio.tv.data.xmplayer.XmpPlanRequiredException
import com.nuvio.tv.data.xmplayer.XmpSyncError
import com.nuvio.tv.data.xmplayer.XmpSyncRepository
import com.nuvio.tv.data.xmplayer.toXmpSyncError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class XmpTrackerUiState(
    val isConnected: Boolean = false,
    /** The XMPlayer profile the connection works in. */
    val profileName: String? = null,
    /** Free | Premium | Pro. */
    val planLabel: String? = null,
    /** False on Free: Nuvio reads XMPlayer but cannot save to it. */
    val canWrite: Boolean = true,
    val isLive: Boolean = false,
    val session: XmpDeviceSession? = null,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private data class XmpTrackerAction(
    val scope: XmpAuthScope? = null,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class XmpTrackerViewModel @Inject constructor(
    private val auth: XmpAuthRepository,
    private val authStore: XmpAuthStore,
    private val sync: XmpSyncRepository,
    private val profiles: ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val action = MutableStateFlow(XmpTrackerAction())
    private var connectionJob: Job? = null
    private var disconnectJob: Job? = null

    val uiState = combine(authStore.state, sync.state, action, profiles.activeProfileId) { authorization, syncState, action, profileId ->
        val current = authorization.scope.profileId == profileId
        val transient = action.takeIf { it.scope == authorization.scope && current } ?: XmpTrackerAction()
        val currentSync = syncState.takeIf { it.scope == authorization.scope && current }
        val connected = current && authorization.isAuthenticated
        val account = authorization.account.takeIf { current }
        XmpTrackerUiState(
            isConnected = connected,
            profileName = account?.profileName,
            planLabel = account?.tierLabel,
            canWrite = account?.write ?: true,
            isLive = currentSync?.isLive == true,
            session = authorization.session.takeIf { current },
            credentialsConfigured = auth.hasRequiredCredentials(),
            isLoading = transient.loading,
            isPolling = transient.polling,
            isSyncing = currentSync?.isLoading == true,
            statusMessage = when {
                currentSync?.isLoading == true -> context.getString(R.string.xmplayer_status_syncing)
                connected && currentSync?.isLive == true -> context.getString(R.string.xmplayer_status_live)
                connected && currentSync?.checkedAtEpochMs != null -> context.getString(
                    R.string.xmplayer_last_synced,
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(currentSync.checkedAtEpochMs!!))
                )
                current && authorization.session != null -> context.getString(R.string.xmplayer_status_enter_code)
                else -> null
            },
            errorMessage = transient.error ?: authorization.error?.takeIf { current }?.message() ?: currentSync?.error?.message()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, XmpTrackerUiState(credentialsConfigured = auth.hasRequiredCredentials()))

    init {
        var previousProfile = profiles.activeProfileId.value
        viewModelScope.launch {
            profiles.activeProfileId.collect { profileId ->
                if (profileId != previousProfile) {
                    connectionJob?.cancel()
                    action.value = XmpTrackerAction()
                    previousProfile = profileId
                }
            }
        }
    }

    fun onConnect() {
        if (connectionJob?.isActive == true || disconnectJob?.isActive == true) return
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profiles.activeProfileId.value || authStore.state.value.isAuthenticated) return
        action.value = XmpTrackerAction(requestedScope, loading = true)
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            var scope = requestedScope
            try {
                authStore.checkScope(scope)
                val session = authStore.state.value.session
                if (session == null || session.expiresAtEpochMs <= System.currentTimeMillis()) auth.startDeviceAuthorization(scope)
                scope = authStore.scope()
                if (scope.profileId != requestedScope.profileId || scope.profileId != profiles.activeProfileId.value) return@launch
                action.value = XmpTrackerAction(scope, polling = true)
                while (isActive && authStore.isCurrent(scope) && scope.profileId == profiles.activeProfileId.value) {
                    val pending = authStore.state.value.session ?: break
                    delay((pending.nextPollAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
                    if (scope.profileId != profiles.activeProfileId.value) break
                    when (auth.pollDeviceAuthorization(scope)) {
                        XmpDevicePollResult.Pending -> Unit
                        XmpDevicePollResult.Authorized -> {
                            action.value = XmpTrackerAction(authStore.scope())
                            sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                            break
                        }
                        XmpDevicePollResult.Expired, XmpDevicePollResult.Denied -> break
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authStore.isCurrent(scope)) action.value = XmpTrackerAction(scope, error = error.messageForUser())
            } finally {
                val current = action.value
                if (current.scope == scope) action.value = current.copy(loading = false, polling = false)
            }
        }
    }

    fun onRetryPolling() = onConnect()

    fun onCancel() {
        connectionJob?.cancel()
        val scope = authStore.scope()
        action.value = XmpTrackerAction()
        viewModelScope.launch(Dispatchers.IO) { authStore.cancelSession(scope = scope) }
    }

    fun onDisconnect() {
        if (disconnectJob?.isActive == true) return
        connectionJob?.cancel()
        val profileId = profiles.activeProfileId.value
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profileId) return
        action.value = XmpTrackerAction(requestedScope, loading = true)
        disconnectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val revoked = auth.disconnect(requestedScope)
                sync.ensureLoaded()
                if (profiles.activeProfileId.value == profileId) action.value = XmpTrackerAction(
                    authStore.scope(), error = if (revoked) null else context.getString(R.string.xmplayer_revoke_failed)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (profiles.activeProfileId.value == profileId) action.value = XmpTrackerAction(authStore.scope(), error = error.messageForUser())
            }
        }
    }

    fun onSyncNow() = sync.refreshAsync(TrackingRefreshIntent.USER_INITIATED)
    fun onAccountOpened() = sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)

    private fun Exception.messageForUser(): String = when (this) {
        is XmpAuthException -> error.message()
        is XmpPlanRequiredException -> context.getString(R.string.xmplayer_plan_required)
        else -> toXmpSyncError().message()
    }

    private fun XmpAuthError.message(): String = context.getString(when (this) {
        XmpAuthError.MISSING_CLIENT_ID -> R.string.xmplayer_missing_client
        XmpAuthError.INVALID_RESPONSE -> R.string.xmplayer_invalid_response
        XmpAuthError.INSUFFICIENT_SCOPE -> R.string.xmplayer_insufficient_scope
        XmpAuthError.CODE_EXPIRED -> R.string.xmplayer_code_expired
        XmpAuthError.ACCESS_DENIED -> R.string.xmplayer_access_denied
        XmpAuthError.ACCOUNT_FULL -> R.string.xmplayer_account_full
        XmpAuthError.AUTHORIZATION_REVOKED -> R.string.xmplayer_authorization_revoked
    })

    private fun XmpSyncError.message(): String = context.getString(when (this) {
        XmpSyncError.UNAVAILABLE -> R.string.xmplayer_unavailable
        XmpSyncError.INVALID_RESPONSE -> R.string.xmplayer_invalid_response
        XmpSyncError.RATE_LIMIT -> R.string.xmplayer_rate_limited
        XmpSyncError.PLAN_REQUIRED -> R.string.xmplayer_plan_required
        XmpSyncError.AUTHORIZATION_REVOKED -> R.string.xmplayer_authorization_revoked
    })
}
