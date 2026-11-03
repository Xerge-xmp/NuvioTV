package com.nuvio.tv.core.probe

import androidx.navigation.NavHostController
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import java.lang.ref.WeakReference

/**
 * Where the app hands its live state to the probe (the `probe` build type only, `src/probe`). Every call is a no-op
 * until the probe switches [enabled] on at process start, so a release build only ever stores nothing here.
 */
object AppProbe {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var nav: WeakReference<NavHostController>? = null

    @Volatile
    private var player: WeakReference<PlayerViewModel>? = null

    private val xmpLog = ArrayDeque<String>()

    fun attachNav(controller: NavHostController) {
        if (enabled) nav = WeakReference(controller)
    }

    fun navController(): NavHostController? = nav?.get()

    fun attachPlayer(viewModel: PlayerViewModel) {
        if (enabled) player = WeakReference(viewModel)
    }

    fun detachPlayer(viewModel: PlayerViewModel) {
        if (player?.get() === viewModel) player = null
    }

    fun player(): PlayerViewModel? = player?.get()

    /** One XMPlayer API exchange, as one line (method, path, status, what was sent and answered — never a token). */
    fun xmpExchange(line: () -> String) {
        if (!enabled) return
        val text = line()
        synchronized(xmpLog) {
            xmpLog.addLast(text)
            while (xmpLog.size > 40) xmpLog.removeFirst()
        }
    }

    fun xmpExchanges(): List<String> = synchronized(xmpLog) { xmpLog.toList() }
}
