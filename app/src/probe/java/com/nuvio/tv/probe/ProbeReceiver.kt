package com.nuvio.tv.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal const val PROBE_TAG = "NuvioProbe"

/** Every answer line is `<id>|<text>`; the last one is `<id>|END ok` or `<id>|END fail <why>`. */
internal class ProbeOut(private val id: String) {
    fun line(text: String) {
        text.split('\n').forEach { Log.i(PROBE_TAG, "$id|$it") }
    }

    fun end(ok: Boolean, why: String = "") {
        Log.i(PROBE_TAG, "$id|END ${if (ok) "ok" else "fail"}${if (why.isNotBlank()) " $why" else ""}")
    }
}

/**
 * `am broadcast -n com.nuvio.tv/com.nuvio.tv.probe.ProbeReceiver --es id <n> --es cmd <command> --es args64 <base64>`
 * (args64: the arguments joined by newlines, base64 — so spaces and quotes need no shell escaping).
 * The command runs on the main thread in the probe's own scope; `tools/nuvio-drive.js` sends and reads these.
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: "0"
        val cmd = intent.getStringExtra("cmd") ?: "help"
        val args = intent.getStringExtra("args64")
            ?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }
            ?.split('\n')?.filter(String::isNotEmpty)
            ?: emptyList()
        val out = ProbeOut(id)
        val app = context.applicationContext
        scope.launch {
            try {
                out.end(ProbeCommands.run(app, cmd, args, out))
            } catch (error: Throwable) {
                out.end(false, "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(300))
            }
        }
    }

    companion object {
        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
