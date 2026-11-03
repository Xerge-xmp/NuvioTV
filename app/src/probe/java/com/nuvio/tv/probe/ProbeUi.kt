package com.nuvio.tv.probe

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.delay

/** One thing on screen a remote (or a finger) can reach, read from the window's Compose semantics. */
internal data class Target(
    val index: Int,
    val window: Int,
    val nodeId: Int,
    val box: Rect,
    val label: String,
    val focusable: Boolean,
    val focused: Boolean,
    val clickable: Boolean,
    val extra: String,
    val node: SemanticsNode,
    /** Shown smaller than it is laid out: its row / list (or the screen edge) cuts it. */
    val clipped: Boolean = false
) {
    val key: String get() = "$window:$nodeId"

    fun describe(screenW: Int, screenH: Int): String = buildString {
        append('t').append(index)
        if (focused) append(" FOCUS")
        append(if (focusable) " focusable" else "").append(if (clickable) " click" else "")
        append(" box=").append(box.left).append(',').append(box.top).append(',').append(box.right).append(',').append(box.bottom)
        if (box.right <= 0 || box.bottom <= 0 || box.left >= screenW || box.top >= screenH) append(" OFF")
        else if (clipped || box.left < 0 || box.top < 0 || box.right > screenW || box.bottom > screenH) append(" CUT")
        if (window > 0) append(" w=").append(window)
        append(" \"").append(label.replace('"', '\'')).append('"')
        if (extra.isNotEmpty()) append(' ').append(extra)
    }
}

internal object ProbeUi {
    private val roots = CopyOnWriteArrayList<WeakReference<ViewRootForTest>>()

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    fun install(app: Application) {
        val previous = ViewRootForTest.onViewCreatedCallback
        ViewRootForTest.onViewCreatedCallback = { root ->
            roots.add(WeakReference(root))
            previous?.invoke(root)
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }
        })
    }

    fun activity(): Activity? = resumed?.get()

    /** The Compose windows on screen, in creation order (a dialog or popup comes after the screen it opens over). */
    fun liveRoots(): List<ViewRootForTest> {
        roots.removeAll { it.get() == null }
        return roots.mapNotNull { it.get() }.filter { it.view.isAttachedToWindow && it.view.isShown }
    }

    /** The window that takes the keys: the one with window focus, else the newest. */
    fun topRoot(): ViewRootForTest? {
        val live = liveRoots()
        return live.lastOrNull { it.view.hasWindowFocus() } ?: live.lastOrNull()
    }

    fun windowKind(root: ViewRootForTest): String {
        val decor = activity()?.window?.decorView
        return if (decor != null && root.view.rootView === decor) "screen" else "dialog"
    }

    fun screenSize(): Pair<Int, Int> {
        val metrics = (activity() ?: return 1920 to 1080).resources.displayMetrics
        val decor = activity()?.window?.decorView
        val w = decor?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val h = decor?.height?.takeIf { it > 0 } ?: metrics.heightPixels
        return w to h
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────────────────

    fun targets(windows: List<ViewRootForTest> = listOfNotNull(topRoot())): List<Target> {
        val all = liveRoots()
        val out = ArrayList<Target>()
        for (root in windows) {
            val window = all.indexOf(root).coerceAtLeast(0)
            val location = IntArray(2).also(root.view::getLocationOnScreen)
            val nodes = runCatching { root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }.getOrElse { emptyList() }
            for (node in nodes) {
                val config = node.config
                val focusable = SemanticsProperties.Focused in config
                val focused = config.getOrNull(SemanticsProperties.Focused) == true
                val clickable = SemanticsActions.OnClick in config
                // A container repeats what is inside it; only what can be reached speaks for its contents.
                val label = if (focusable || clickable) label(node) else ownText(node).take(160)
                if (!focusable && !clickable && label.isEmpty()) continue
                val bounds = node.boundsInRoot
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                val box = Rect(
                    location[0] + bounds.left.toInt(), location[1] + bounds.top.toInt(),
                    location[0] + bounds.right.toInt(), location[1] + bounds.bottom.toInt()
                )
                val clipped = bounds.width < node.size.width - 2 || bounds.height < node.size.height - 2
                out += Target(out.size, window, node.id, box, label, focusable, focused, clickable, extra(node), node, clipped)
            }
        }
        return out
    }

    fun focused(targets: List<Target>): Target? =
        targets.filter { it.focused }.minByOrNull { it.box.width().toLong() * it.box.height() }

    private fun ownText(node: SemanticsNode): String {
        val config = node.config
        val parts = ArrayList<String>()
        config.getOrNull(SemanticsProperties.Text)?.map(AnnotatedString::text)?.filter(String::isNotBlank)?.let(parts::addAll)
        config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf(String::isNotBlank)?.let(parts::add)
        if (parts.isEmpty()) config.getOrNull(SemanticsProperties.ContentDescription)?.filter(String::isNotBlank)?.let(parts::addAll)
        return parts.joinToString(" · ")
    }

    /** What the element says: its own (merged) text, else the first texts inside it. */
    fun label(node: SemanticsNode): String {
        val own = ownText(node)
        if (own.isNotEmpty()) return own.take(160)
        val found = ArrayList<String>()
        fun walk(n: SemanticsNode, depth: Int) {
            if (found.size >= 4 || depth > 6) return
            for (child in n.children) {
                ownText(child).takeIf(String::isNotEmpty)?.let(found::add)
                walk(child, depth + 1)
            }
        }
        walk(node, 0)
        return found.joinToString(" · ").take(160)
    }

    private fun extra(node: SemanticsNode): String {
        val config = node.config
        val parts = ArrayList<String>()
        config.getOrNull(SemanticsProperties.Role)?.let { parts += "role=$it" }
        config.getOrNull(SemanticsProperties.TestTag)?.let { parts += "tag=$it" }
        if (config.getOrNull(SemanticsProperties.Selected) == true) parts += "selected"
        config.getOrNull(SemanticsProperties.ToggleableState)?.let { parts += "toggle=$it" }
        config.getOrNull(SemanticsProperties.StateDescription)?.let { parts += "state=\"$it\"" }
        if (SemanticsProperties.Disabled in config) parts += "disabled"
        if (SemanticsProperties.EditableText in config) parts += "editable"
        if (SemanticsProperties.HorizontalScrollAxisRange in config) parts += "scroll=h"
        if (SemanticsProperties.VerticalScrollAxisRange in config) parts += "scroll=v"
        return parts.joinToString(" ")
    }

    /** The element a query names: `=text` exact, else contains (case-insensitive); a focusable element first. */
    fun find(targets: List<Target>, query: String): Target? {
        val exact = query.startsWith("=")
        val q = query.removePrefix("=").trim()
        fun matches(t: Target) = if (exact) {
            t.label.equals(q, ignoreCase = true) || t.label.split(" · ").any { it.equals(q, ignoreCase = true) }
        } else t.label.contains(q, ignoreCase = true)
        val hits = targets.filter(::matches)
        hits.firstOrNull { it.focusable }?.let { return it }
        val hit = hits.firstOrNull() ?: return null
        // A label inside a focusable card: the card is what takes focus.
        targets.filter { it.focusable && it.window == hit.window && it.box.contains(hit.box) }
            .minByOrNull { it.box.width().toLong() * it.box.height() }?.let { return it }
        // A heading (a row title): the first element under it.
        return targets.filter { it.focusable && it.window == hit.window && it.box.top >= hit.box.top && it.box.right > hit.box.left }
            .minWithOrNull(compareBy<Target>({ it.box.top - hit.box.top }, { abs(it.box.left - hit.box.left) })) ?: hit
    }

    // ── keys ─────────────────────────────────────────────────────────────────────────────────────────────────

    fun keyCode(name: String): Int {
        val upper = name.trim().uppercase()
        val full = if (upper.startsWith("KEYCODE_")) upper else "KEYCODE_$upper"
        val code = KeyEvent.keyCodeFromString(full)
        require(code != KeyEvent.KEYCODE_UNKNOWN) { "unknown key $name" }
        return code
    }

    /** One press (down + up) into the window that has the keys — the path a remote's key takes inside the app. */
    fun press(code: Int): Boolean {
        val target: View = topRoot()?.view?.rootView ?: activity()?.window?.decorView ?: return false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        val up = KeyEvent(now, now + 30, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        val handled = target.dispatchKeyEvent(down)
        target.dispatchKeyEvent(up)
        return handled
    }

    /** Waits for the press to land: focus moves at once, a scroll that brings the element in takes a few frames. */
    suspend fun settle() {
        delay(160)
        val until = SystemClock.uptimeMillis() + 800
        while (SystemClock.uptimeMillis() < until && liveRoots().any { it.hasPendingMeasureOrLayout }) delay(40)
        delay(60)
    }

    // ── navigating by name ───────────────────────────────────────────────────────────────────────────────────

    private fun Rect.cx() = (left + right) / 2
    private fun Rect.cy() = (top + bottom) / 2

    /**
     * The D-pad key from the focused element toward the target. The axis follows the layout (same row → sideways,
     * same column → up/down, else [vertical]); [forced] (after a press that did not move) overrides it.
     */
    private fun step(from: Rect, to: Rect, vertical: Boolean, forced: Boolean?): Int? {
        val rowOverlap = to.top < from.bottom && to.bottom > from.top
        val colOverlap = to.left < from.right && to.right > from.left
        val dx = to.cx() - from.cx()
        val dy = to.cy() - from.cy()
        val horizontal = when {
            forced != null && (if (forced) dy != 0 else dx != 0) -> !forced
            rowOverlap && !colOverlap -> true
            colOverlap && !rowOverlap -> false
            dx == 0 -> false
            dy == 0 -> true
            else -> !vertical
        }
        return when {
            horizontal && dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            horizontal && dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            !horizontal && dy > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
            !horizontal && dy < 0 -> KeyEvent.KEYCODE_DPAD_UP
            else -> null
        }
    }

    private fun keyName(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    /**
     * Moves focus to the element [query] names with D-pad presses, each one checked against the focus it produced.
     * When the element is not on screen yet, [seek] (a key) is pressed until it is. Null when it cannot be reached.
     */
    suspend fun navigate(query: String, seek: Int?, max: Int, out: ProbeOut): Target? {
        var vertical = true
        var stuckOnAxis = 0
        var forced: Boolean? = null   // true = up/down, false = sideways, for the press after one that did not move
        repeat(max) { press ->
            val targets = targets()
            val focus = focused(targets)
            val goal = find(targets, query)
            if (goal != null && focus != null && (goal.key == focus.key || goal.box.contains(focus.box))) {
                val (w, h) = screenSize()
                out.line("nav reached after $press press(es): ${focus.describe(w, h)}")
                return focus
            }
            val code = when {
                goal == null && seek == null -> {
                    out.line("nav \"$query\" is not on screen (pass seek=<KEY> to scroll toward it)")
                    return null
                }
                goal == null -> seek!!
                focus == null -> KeyEvent.KEYCODE_DPAD_DOWN
                else -> step(focus.box, goal.box, vertical, forced) ?: KeyEvent.KEYCODE_DPAD_DOWN
            }
            val wasVertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            press(code)
            settle()
            val after = focused(targets())
            val moved = after?.key != focus?.key || after?.box != focus?.box
            out.line("nav press=${press + 1} ${keyName(code)} focus=${if (after == null) "none" else "\"${after.label.take(60)}\""}${if (moved) "" else " (did not move)"}")
            if (!moved) {
                stuckOnAxis += 1
                if (goal == null) {
                    out.line("nav \"$query\" not found: the list ends here")
                    return null
                }
                if (stuckOnAxis >= 2) {
                    out.line("nav stuck: focus does not move toward \"${goal.label.take(60)}\"")
                    return null
                }
                forced = !wasVertical
                vertical = !wasVertical
            } else {
                stuckOnAxis = 0
                forced = null
            }
        }
        out.line("nav gave up after $max presses")
        return null
    }

    fun requestFocus(target: Target): Boolean =
        target.node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true

    fun setText(target: Target, text: String): Boolean =
        target.node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text)) == true
}
