package com.nuvio.tv.probe

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import com.nuvio.tv.core.deeplink.DeepLinkHandler
import com.nuvio.tv.core.deeplink.DeepLinkInstallResult
import com.nuvio.tv.core.probe.AppProbe
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.xmplayer.XmpAuthRepository
import com.nuvio.tv.data.xmplayer.XmpAuthStore
import com.nuvio.tv.data.xmplayer.XmpDevicePollResult
import com.nuvio.tv.data.xmplayer.XmpSyncRepository
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.player.PlayerEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ProbeEntryPoint {
    fun xmpAuthStore(): XmpAuthStore
    fun xmpAuth(): XmpAuthRepository
    fun xmpSync(): XmpSyncRepository
    fun trackingSources(): TrackingSourceController
    fun deepLinks(): DeepLinkHandler
    fun addons(): AddonRepository
    fun profiles(): ProfileManager
    fun servers(): com.nuvio.tv.data.mediaserver.ServerRepository
}

/** Arguments: positional words plus `key=value` options and `--flags`. */
private class Args(raw: List<String>) {
    val words = raw.filter { !it.startsWith("--") && !OPTION.matches(it) }
    val options = raw.mapNotNull { OPTION.matchEntire(it) }.associate { it.groupValues[1] to it.groupValues[2] }
    val flags = raw.filter { it.startsWith("--") }.map { it.removePrefix("--") }.toSet()
    fun text() = words.joinToString(" ")
    fun int(name: String) = options[name]?.toIntOrNull()

    companion object {
        val OPTION = Regex("([a-zA-Z][a-zA-Z0-9]*)=(.*)", RegexOption.DOT_MATCHES_ALL)
    }
}

internal object ProbeCommands {
    private const val HELP = """commands (answers on logcat tag NuvioProbe):
  where                          screen route, back stack, windows, focused element
  targets [text] [--all]         every focusable / clickable / labelled element with its screen box
  key <KEY> [count]              press a key in the app (DPAD_DOWN, DPAD_CENTER, BACK, MEDIA_PLAY_PAUSE …)
  nav <text|=text> [seek=KEY] [max=n]   move focus there with D-pad presses, each one verified
  click <text|=text> [seek=KEY]  nav, then DPAD_CENTER
  focus <text|=text>             give it focus directly (no key presses)
  type <text>                    set the focused (or first) text field
  back                           the Back key
  goto <name|route>              home search discover library settings tracking addons account profiles playback
                                 detail type=<movie|series> id=<id>   (any other word is used as the route)
  routes                         every route of the app
  play url=<stream> title=<t> [contentId= type= videoId= season= episode= year= name=]
  player | pause | resume | seek <seconds> | stop
  xmp                            XMPlayer connection, sync state, newest history, last API exchanges
  xmp-log [n]                    the last n XMPlayer API exchanges
  xmp-find <text|key>            every history row Nuvio holds whose key or title contains it
  xmp-connect [wait=<seconds>]   start the device sign-in, print the code, wait until it is approved
  xmp-disconnect | xmp-sync
  sources [progress=<xmplayer|nuvio|trakt|simkl|mdblist>] [library=<xmplayer|local|trakt|simkl|mdblist>]
  addons | addon-install <manifest url> | addon-remove <url>
  profiles | profile <id>
  servers [refresh]              media-server connections (XMPlayer, Jellyfin, …): libraries, which are Home rows, failures"""

    private fun ep(context: Context) = EntryPointAccessors.fromApplication(context, ProbeEntryPoint::class.java)

    private fun time(epochMs: Long?) = epochMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() } ?: "-"
    private fun sec(ms: Long) = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    private fun quote(text: String?) = "\"${text.orEmpty().replace('"', '\'')}\""

    /** A stream address without its query (debrid links carry keys there). */
    private fun address(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "-"
        return "${uri.scheme}://${uri.host}${uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()}${uri.path.orEmpty()}"
    }

    suspend fun run(context: Context, cmd: String, raw: List<String>, out: ProbeOut): Boolean {
        val args = Args(raw)
        return when (cmd.lowercase()) {
            "help" -> { out.line(HELP); true }
            "where" -> where(out)
            "targets" -> targets(args, out)
            "key" -> key(args, out)
            "nav" -> nav(args, out) != null
            "click" -> click(args, out)
            "focus" -> focus(args, out)
            "type" -> type(args, out)
            "back" -> { ProbeUi.press(KeyEvent.KEYCODE_BACK); ProbeUi.settle(); where(out) }
            "goto" -> goto(args, out)
            "routes" -> routes(out)
            "play" -> play(args, out)
            "player" -> player(out)
            "pause", "resume" -> playPause(cmd == "resume", out)
            "seek" -> seek(args, out)
            "stop" -> stop(out)
            "xmp" -> xmp(context, out)
            "xmp-log" -> xmpLog(args.words.firstOrNull()?.toIntOrNull() ?: 20, out)
            "xmp-find" -> xmpFind(context, args, out)
            "xmp-connect" -> xmpConnect(context, args, out)
            "xmp-disconnect" -> xmpDisconnect(context, out)
            "xmp-sync" -> { ep(context).xmpSync().refresh(TrackingRefreshIntent.USER_INITIATED); xmp(context, out) }
            "sources" -> sources(context, args, out)
            "addons" -> addons(context, out)
            "addon-install" -> addonInstall(context, args, out)
            "addon-remove" -> addonRemove(context, args, out)
            "profiles" -> profiles(context, out)
            "servers" -> servers(context, args, out)
            "profile" -> profile(context, args, out)
            else -> { out.line("unknown command $cmd\n$HELP"); false }
        }
    }

    // ── screen ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun where(out: ProbeOut): Boolean {
        val nav = AppProbe.navController()
        val entry = nav?.currentBackStackEntry
        val route = entry?.destination?.route
        out.line("route=${route?.substringBefore('/')?.substringBefore('?') ?: "-"} pattern=${route ?: "-"}")
        entry?.arguments?.let { bundle ->
            val shown = bundle.keySet().filter { it !in SECRET_ARGS }.mapNotNull { k ->
                @Suppress("DEPRECATION")
                bundle.get(k)?.toString()?.takeIf { it.isNotBlank() && it != "null" && it != "false" }?.let { "$k=${quote(it.take(80))}" }
            }
            if (shown.isNotEmpty()) out.line("args ${shown.joinToString(" ")}")
        }
        nav?.currentBackStack?.value?.mapNotNull { it.destination.route?.substringBefore('/')?.substringBefore('?') }
            ?.takeIf { it.isNotEmpty() }?.let { out.line("backstack=${it.joinToString(">")}") }
        val roots = ProbeUi.liveRoots()
        val top = ProbeUi.topRoot()
        out.line("windows=${roots.size} keys=${top?.let { "${roots.indexOf(it)}(${ProbeUi.windowKind(it)})" } ?: "-"} activity=${ProbeUi.activity()?.javaClass?.simpleName ?: "-"}")
        val (w, h) = ProbeUi.screenSize()
        val focus = ProbeUi.focused(ProbeUi.targets())
        out.line("focus=${focus?.describe(w, h) ?: "none"}")
        AppProbe.player()?.let { out.line("player ${playerState(it)}") }
        return true
    }

    private val SECRET_ARGS = setOf("streamUrl", "headers", "cloudSessionToken", "sources", "videoHash", "infoHash", "launchStartedAtMs")

    private fun targets(args: Args, out: ProbeOut): Boolean {
        val windows = if ("all" in args.flags) ProbeUi.liveRoots() else listOfNotNull(ProbeUi.topRoot())
        val (w, h) = ProbeUi.screenSize()
        val filter = args.text()
        val list = ProbeUi.targets(windows).filter { filter.isEmpty() || it.label.contains(filter.removePrefix("="), ignoreCase = true) }
        out.line("screen=${w}x$h targets=${list.size}")
        list.forEach { out.line(it.describe(w, h)) }
        return true
    }

    private suspend fun key(args: Args, out: ProbeOut): Boolean {
        val code = ProbeUi.keyCode(args.words.firstOrNull() ?: return false.also { out.line("key <KEY> [count]") })
        val count = args.words.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 50) ?: 1
        repeat(count) { ProbeUi.press(code); ProbeUi.settle() }
        val (w, h) = ProbeUi.screenSize()
        out.line("focus=${ProbeUi.focused(ProbeUi.targets())?.describe(w, h) ?: "none"}")
        return true
    }

    private suspend fun nav(args: Args, out: ProbeOut): Target? {
        val query = args.text().takeIf(String::isNotBlank) ?: return null.also { out.line("nav <text>") }
        val seek = args.options["seek"]?.let(ProbeUi::keyCode)
        return ProbeUi.navigate(query, seek, args.int("max")?.coerceIn(1, 200) ?: 40, out)
    }

    private suspend fun click(args: Args, out: ProbeOut): Boolean {
        nav(args, out) ?: return false
        ProbeUi.press(KeyEvent.KEYCODE_DPAD_CENTER)
        ProbeUi.settle()
        delay(250)
        return where(out)
    }

    private suspend fun focus(args: Args, out: ProbeOut): Boolean {
        val target = ProbeUi.find(ProbeUi.targets(), args.text()) ?: return false.also { out.line("not on screen: ${args.text()}") }
        val ok = ProbeUi.requestFocus(target)
        ProbeUi.settle()
        val (w, h) = ProbeUi.screenSize()
        out.line("focus ${if (ok) "requested" else "refused"}: ${ProbeUi.focused(ProbeUi.targets())?.describe(w, h) ?: "none"}")
        return ok
    }

    private suspend fun type(args: Args, out: ProbeOut): Boolean {
        val targets = ProbeUi.targets()
        val field = targets.firstOrNull { it.focused && "editable" in it.extra } ?: targets.firstOrNull { "editable" in it.extra }
            ?: return false.also { out.line("no text field on screen") }
        val ok = ProbeUi.setText(field, args.text())
        ProbeUi.settle()
        out.line("type ${if (ok) "set" else "refused"} into ${quote(field.label)}")
        return ok
    }

    private suspend fun goto(args: Args, out: ProbeOut): Boolean {
        val nav = AppProbe.navController() ?: return false.also { out.line("no navigation yet (app not on screen)") }
        val name = args.words.firstOrNull() ?: return false.also { out.line("goto <name|route>") }
        val route = when (name.lowercase()) {
            "home" -> Screen.Home.route
            "search" -> Screen.Search.route
            "discover" -> Screen.Discover.route
            "library" -> Screen.Library.route
            "calendar" -> Screen.Calendar.route
            "settings" -> Screen.Settings.route
            "tracking", "trakt" -> Screen.Tracking.route
            "addons" -> Screen.AddonManager.route
            "account" -> Screen.Account.route
            "profiles" -> Screen.ProfileSelection.route
            "playback" -> Screen.PlaybackSettings.route
            "detail" -> Screen.Detail.createRoute(
                itemId = args.options["id"] ?: return false.also { out.line("goto detail type=<movie|series> id=<id>") },
                itemType = args.options["type"] ?: "movie"
            )
            else -> name
        }
        nav.navigate(route) { launchSingleTop = true }
        ProbeUi.settle()
        delay(300)
        return where(out)
    }

    private fun routes(out: ProbeOut): Boolean {
        val nav = AppProbe.navController() ?: return false.also { out.line("no navigation yet") }
        nav.graph.mapNotNull { it.route }.sorted().forEach { out.line(it) }
        return true
    }

    // ── player ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun playerState(vm: com.nuvio.tv.ui.screens.player.PlayerViewModel): String {
        val s = vm.uiState.value
        val t = vm.playbackTimeline.value
        val state = when {
            s.error != null -> "error"
            s.playbackEnded -> "ended"
            s.isPlaying -> "playing"
            s.isBuffering -> "buffering"
            else -> "paused"
        }
        return "state=$state position=${sec(t.currentPosition)} duration=${sec(t.duration)} buffered=${sec(t.bufferedPosition)}" +
            " firstFrame=${vm.controller.hasRenderedFirstFrame} engine=${s.internalPlayerEngine}${if (t.isLive) " live" else ""}"
    }

    private fun player(out: ProbeOut): Boolean {
        val vm = AppProbe.player() ?: return true.also { out.line("player none") }
        val s = vm.uiState.value
        out.line("player ${playerState(vm)}")
        out.line("player title=${quote(s.title)} content=${quote(s.contentName)} type=${s.contentType ?: "-"} year=${s.releaseYear ?: "-"}")
        out.line("player stream=${quote(s.currentStreamName)} addon=${quote(s.currentStreamAddonName)} url=${address(s.currentStreamUrl)}")
        if (s.showLoadingOverlay || s.loadingMessage != null) {
            out.line("player loading overlay=${s.showLoadingOverlay} message=${quote(s.loadingMessage)} progress=${s.loadingProgress ?: "-"}")
        }
        s.error?.let { out.line("player error=${quote(it)}") }
        out.line("player controls=${s.showControls} pauseOverlay=${s.showPauseOverlay} speed=${s.playbackSpeed}")
        return true
    }

    private suspend fun play(args: Args, out: ProbeOut): Boolean {
        val nav = AppProbe.navController() ?: return false.also { out.line("no navigation yet (app not on screen)") }
        val url = args.options["url"] ?: return false.also { out.line("play url=<stream> title=<title> …") }
        val title = args.options["title"] ?: "Probe test"
        nav.navigate(
            Screen.Player.createRoute(
                streamUrl = url,
                title = title,
                year = args.options["year"],
                contentId = args.options["contentId"],
                contentType = args.options["type"],
                contentName = args.options["name"] ?: title,
                videoId = args.options["videoId"] ?: args.options["contentId"],
                season = args.int("season"),
                episode = args.int("episode"),
                startFromBeginning = "begin" in args.flags
            )
        )
        val until = System.currentTimeMillis() + (args.int("wait")?.coerceIn(1, 120) ?: 20) * 1000L
        while (System.currentTimeMillis() < until) {
            val vm = AppProbe.player()
            if (vm != null && (vm.uiState.value.isPlaying || vm.uiState.value.error != null)) break
            delay(250)
        }
        return player(out) && AppProbe.player()?.uiState?.value?.isPlaying == true
    }

    private suspend fun playPause(play: Boolean, out: ProbeOut): Boolean {
        val vm = AppProbe.player() ?: return false.also { out.line("player none") }
        if (vm.uiState.value.isPlaying != play) vm.onEvent(PlayerEvent.OnPlayPause)
        delay(600)
        return player(out)
    }

    private suspend fun seek(args: Args, out: ProbeOut): Boolean {
        val vm = AppProbe.player() ?: return false.also { out.line("player none") }
        val seconds = args.words.firstOrNull()?.toDoubleOrNull() ?: return false.also { out.line("seek <seconds>") }
        vm.onEvent(PlayerEvent.OnSeekTo((seconds * 1000).toLong()))
        delay(800)
        return player(out)
    }

    private suspend fun stop(out: ProbeOut): Boolean {
        val nav = AppProbe.navController() ?: return false
        val route = nav.currentBackStackEntry?.destination?.route.orEmpty()
        if (!route.startsWith("player")) return false.also { out.line("not in the player (route=${route.substringBefore('/')})") }
        nav.popBackStack()
        ProbeUi.settle()
        delay(500)
        return where(out)
    }

    // ── XMPlayer ─────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun xmp(context: Context, out: ProbeOut): Boolean {
        val e = ep(context)
        val auth = e.xmpAuthStore().state.value
        val account = auth.account
        out.line(
            "xmp connected=${auth.isAuthenticated} nuvioProfile=${auth.scope.profileId} xmpProfile=${quote(account?.profileName)}" +
                " tier=${account?.tier ?: "-"} write=${account?.write ?: "-"} stream=${account?.stream ?: "-"} readsPerMinute=${account?.readsPerMinute ?: "-"}"
        )
        auth.session?.let { out.line("xmp pending code=${it.userCode} uri=${it.verificationUriComplete} expires=${time(it.expiresAtEpochMs)}") }
        auth.error?.let { out.line("xmp authError=$it") }
        val sync = e.xmpSync().state.value
        val snapshot = sync.snapshot
        out.line(
            "xmp sync live=${sync.isLive} loading=${sync.isLoading} error=${sync.error ?: "-"} checked=${time(sync.checkedAtEpochMs)}" +
                " cursor=${snapshot?.cursor ?: "-"} history=${snapshot?.history?.size ?: 0} favorites=${snapshot?.favorites?.size ?: 0}" +
                " collections=${snapshot?.collections?.size ?: 0}"
        )
        val sources = e.trackingSources()
        out.line("xmp sources progress=${withContext(Dispatchers.IO) { sources.watchProgressSource.first() }} library=${withContext(Dispatchers.IO) { sources.librarySourceMode.first() }}")
        snapshot?.history?.values?.sortedByDescending { it.lastWatchedAt }?.take(5)?.forEach { h ->
            val m = h.media
            out.line(
                "xmp history key=${h.key} ${m.type} ${quote(m.title)} year=${m.year ?: "-"} imdb=${m.imdb ?: "-"} tmdb=${m.tmdb ?: "-"}" +
                    "${if (h.isEpisode) " s${h.season}e${h.episode}" else ""} progress=${"%.4f".format(java.util.Locale.US, h.progress)}" +
                    " completed=${h.completed} at=${time(h.lastWatchedAt)}"
            )
        }
        AppProbe.xmpExchanges().takeLast(5).forEach { out.line("xmp api $it") }
        return true
    }

    private fun xmpFind(context: Context, args: Args, out: ProbeOut): Boolean {
        val q = args.text().takeIf(String::isNotBlank) ?: return false.also { out.line("xmp-find <text|key>") }
        val history = ep(context).xmpSync().state.value.snapshot?.history.orEmpty()
        val hits = history.values.filter { it.key.contains(q, ignoreCase = true) || it.media.title.orEmpty().contains(q, ignoreCase = true) }
        out.line("xmp-find \"$q\" rows=${hits.size} of ${history.size}")
        hits.sortedByDescending { it.lastWatchedAt }.forEach { h ->
            val m = h.media
            out.line(
                "xmp row key=${h.key} ${m.type} ${quote(m.title)} year=${m.year ?: "-"} imdb=${m.imdb ?: "-"} tmdb=${m.tmdb ?: "-"}" +
                    "${if (h.isEpisode) " s${h.season}e${h.episode}" else ""} contentId=${m.contentId.ifEmpty { "-" }}" +
                    " progress=${"%.4f".format(java.util.Locale.US, h.progress)} at=${time(h.lastWatchedAt)}"
            )
        }
        return true
    }

    private fun xmpLog(n: Int, out: ProbeOut): Boolean {
        val log = AppProbe.xmpExchanges()
        if (log.isEmpty()) out.line("xmp api (no exchanges yet)")
        log.takeLast(n.coerceIn(1, 40)).forEach { out.line("xmp api $it") }
        return true
    }

    private suspend fun xmpConnect(context: Context, args: Args, out: ProbeOut): Boolean {
        val e = ep(context)
        val store = e.xmpAuthStore()
        val auth = e.xmpAuth()
        if (store.state.value.isAuthenticated) {
            out.line("xmp-connect already connected as ${quote(store.state.value.account?.profileName)}")
            return true
        }
        val deadline = System.currentTimeMillis() + (args.int("wait")?.coerceIn(5, 900) ?: 300) * 1000L
        return withContext(Dispatchers.IO) {
            var scope = store.scope()
            val existing = store.state.value.session
            if (existing == null || existing.expiresAtEpochMs <= System.currentTimeMillis()) auth.startDeviceAuthorization(scope)
            scope = store.scope()
            val session = store.state.value.session ?: return@withContext false.also { out.line("xmp-connect no session") }
            out.line("xmp-connect code=${session.userCode} uri=${session.verificationUriComplete}")
            while (System.currentTimeMillis() < deadline && store.isCurrent(scope)) {
                val pending = store.state.value.session ?: break
                delay((pending.nextPollAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
                when (auth.pollDeviceAuthorization(scope)) {
                    XmpDevicePollResult.Pending -> Unit
                    XmpDevicePollResult.Authorized -> {
                        e.xmpSync().refresh(TrackingRefreshIntent.USER_INITIATED)
                        val account = store.state.value.account
                        out.line("xmp-connect authorized xmpProfile=${quote(account?.profileName)} tier=${account?.tier ?: "-"}")
                        return@withContext true
                    }
                    XmpDevicePollResult.Expired -> return@withContext false.also { out.line("xmp-connect expired") }
                    XmpDevicePollResult.Denied -> return@withContext false.also { out.line("xmp-connect denied") }
                }
            }
            out.line("xmp-connect not approved in time")
            false
        }
    }

    private suspend fun xmpDisconnect(context: Context, out: ProbeOut): Boolean {
        val revoked = withContext(Dispatchers.IO) { ep(context).xmpAuth().disconnect() }
        out.line("xmp-disconnect revoked=$revoked")
        return xmp(context, out)
    }

    // ── sources, addons, profiles ────────────────────────────────────────────────────────────────────────────

    private suspend fun sources(context: Context, args: Args, out: ProbeOut): Boolean {
        val controller = ep(context).trackingSources()
        withContext(Dispatchers.IO) {
            args.options["progress"]?.let { name ->
                val source = if (name.equals("nuvio", true)) WatchProgressSource.NUVIO_SYNC
                else WatchProgressSource.valueOf(name.uppercase())
                controller.selectWatchProgressSource(source)
            }
            args.options["library"]?.let { name -> controller.selectLibrarySourceMode(LibrarySourceMode.valueOf(name.uppercase())) }
        }
        out.line("sources progress=${controller.watchProgressSource.first()} library=${controller.librarySourceMode.first()}")
        return true
    }

    private suspend fun addons(context: Context, out: ProbeOut): Boolean {
        val list = withContext(Dispatchers.IO) { ep(context).addons().getInstalledAddons().first() }
        out.line("addons=${list.size}")
        list.forEach { out.line("addon ${quote(it.displayName)} version=${it.version} url=${it.baseUrl}") }
        return true
    }

    private suspend fun addonInstall(context: Context, args: Args, out: ProbeOut): Boolean {
        val url = args.words.firstOrNull() ?: return false.also { out.line("addon-install <manifest url>") }
        val result = withContext(Dispatchers.IO) { ep(context).deepLinks().installAddon(url) }
        out.line("addon-install ${if (result is DeepLinkInstallResult.Success) "ok" else "failed"} ${quote(result.message)}")
        return result is DeepLinkInstallResult.Success && addons(context, out)
    }

    private suspend fun addonRemove(context: Context, args: Args, out: ProbeOut): Boolean {
        val url = args.words.firstOrNull() ?: return false.also { out.line("addon-remove <url>") }
        withContext(Dispatchers.IO) { ep(context).addons().removeAddon(url) }
        return addons(context, out)
    }

    private suspend fun servers(context: Context, args: Args, out: ProbeOut): Boolean {
        val repository = ep(context).servers()
        repository.ensureLoaded()
        if ("refresh" in args.words) {
            repository.uiState.value.connections.forEach { c ->
                runCatching { withContext(Dispatchers.IO) { repository.refreshLibraries(c.id) } }
                    .onFailure { out.line("server ${c.id} refresh failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
            }
        }
        val state = repository.uiState.value
        out.line("servers=${state.connections.size}")
        val profileId = ep(context).profiles().activeProfileId.value
        repository.syncSnapshot(profileId)?.let { snapshot ->
            out.line("nuvio-cloud-sync would carry servers=${snapshot.servers.size} providers=${snapshot.servers.map { it.providerId }.distinct()}")
        }
        state.connections.forEach { c ->
            out.line(
                "server id=${c.id} provider=${c.providerId} name=${quote(c.name)} user=${quote(c.userName)} enabled=${c.enabled}" +
                    " catalogMetadata=${c.useCatalogMetadata} libraries=${c.libraries.size} home=${c.libraries.count { it.home && it.selected }}" +
                    " failure=${state.failures[c.id] ?: "-"}"
            )
            c.libraries.take(60).forEach { l ->
                out.line("  library ${l.kind} ${quote(l.name)} id=${l.id}${if (l.home) " HOME" else ""}${if (!l.selected) " unselected" else ""}")
            }
        }
        return true
    }

    private fun profiles(context: Context, out: ProbeOut): Boolean {
        val manager = ep(context).profiles()
        manager.profiles.value.forEach { p ->
            out.line("profile id=${p.id} name=${quote(p.name)}${if (p.id == manager.activeProfileId.value) " ACTIVE" else ""}")
        }
        return true
    }

    private suspend fun profile(context: Context, args: Args, out: ProbeOut): Boolean {
        val id = args.words.firstOrNull()?.toIntOrNull() ?: return false.also { out.line("profile <id>") }
        val manager = ep(context).profiles()
        withContext(Dispatchers.IO) { manager.setActiveProfile(id) }
        delay(300)
        return profiles(context, out) && manager.activeProfileId.value == id
    }
}
