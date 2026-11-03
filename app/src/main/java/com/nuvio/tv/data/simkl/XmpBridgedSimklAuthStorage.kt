package com.nuvio.tv.data.simkl

import com.nuvio.tv.data.xmplayer.XMP_BRIDGE_TOKEN
import com.nuvio.tv.data.xmplayer.XmpBridge
import com.nuvio.tv.data.xmplayer.XmpBridgeProvider
import com.nuvio.tv.data.xmplayer.XmpBridgeState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Simkl through XMPlayer: the profile's own Simkl sign-in when it has one; otherwise, when the connected XMPlayer profile
 * has Simkl linked (and the plan allows it), Simkl through XMPlayer — no Simkl token on this device. Never both, so
 * nothing is scrobbled twice. Identity (name, settings watermark) is kept in the device store either way.
 */
@Singleton
class XmpBridgedSimklAuthStorage @Inject constructor(
    private val base: AndroidSimklAuthStorage,
    private val bridge: XmpBridge
) : SimklAuthStorage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val state: StateFlow<SimklAuthState> = combine(base.state, bridge.state, ::merged)
        .stateIn(scope, SharingStarted.Eagerly, merged(base.state.value, bridge.state.value))

    /** Simkl runs through XMPlayer for the active profile right now. */
    val viaXmp: Boolean get() = base.authorization() == null && bridge.activeNow(XmpBridgeProvider.SIMKL)

    override fun currentScope(): SimklAuthScope = base.currentScope()

    override fun isCurrent(scope: SimklAuthScope): Boolean = base.isCurrent(scope)

    override fun authorization(): SimklAuthorization? =
        base.authorization() ?: if (viaXmp) SimklAuthorization(base.currentScope(), XMP_BRIDGE_TOKEN) else null

    override fun savePinSession(session: SimklPinSession, scope: SimklAuthScope): Boolean = base.savePinSession(session, scope)

    override fun clearPinSession(error: SimklAuthError?, scope: SimklAuthScope): Boolean = base.clearPinSession(error, scope)

    override fun completePinAuthorization(token: String, scope: SimklAuthScope): Boolean =
        base.completePinAuthorization(token, scope)

    override fun saveIdentity(
        username: String?,
        accountId: Long?,
        settingsActivityWatermark: String?,
        scope: SimklAuthScope
    ): Boolean = base.saveIdentity(username, accountId, settingsActivityWatermark, scope)

    override fun recordSettingsActivityWatermark(watermark: String, scope: SimklAuthScope): Boolean =
        base.recordSettingsActivityWatermark(watermark, scope)

    /**
     * On a bridged profile: a user's disconnect (no error) stops using XMPlayer's Simkl link on this profile (the link stays
     * in XMPlayer); a refused call (an error) means the link is gone — shown as not linked and read again.
     */
    override fun clearAuth(error: SimklAuthError?, scope: SimklAuthScope, expectedAccessToken: String?): Boolean {
        if (expectedAccessToken == XMP_BRIDGE_TOKEN || (expectedAccessToken == null && viaXmp)) {
            if (!base.isCurrent(scope)) return false
            if (error == null) bridge.setOptedOutAsync(XmpBridgeProvider.SIMKL, true) else bridge.onLinkLost(XmpBridgeProvider.SIMKL)
            base.clearAuth(error, scope, null)
            return true
        }
        return base.clearAuth(error, scope, expectedAccessToken)
    }

    override fun removeProfile(profileId: Int) = base.removeProfile(profileId)

    override fun clearAllProfiles() = base.clearAllProfiles()

    private fun merged(direct: SimklAuthState, bridgeState: XmpBridgeState): SimklAuthState {
        if (direct.isAuthenticated || !bridgeState.active(XmpBridgeProvider.SIMKL)) return direct
        return direct.copy(
            isAuthenticated = true,
            username = direct.username ?: bridgeState.simkl.account,
            viaXmp = true
        )
    }
}
