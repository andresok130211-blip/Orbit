package app.orbit.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class OrbitAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OrbitAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun find(
        node: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, pred)?.let { return it }
        }
        return null
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val t = text.lowercase()
        val node = find(root) { n ->
            n.text?.toString()?.lowercase()?.contains(t) == true ||
                n.contentDescription?.toString()?.lowercase()?.contains(t) == true
        } ?: return false

        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val r = Rect()
        node.getBoundsInScreen(r)
        tap(r.exactCenterX(), r.exactCenterY())
        return true
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: find(root) { it.isEditable } ?: return false
        val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun gesture(path: Path, duration: Long) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(g, null, null)
    }

    fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 50)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 300)

    private val w get() = resources.displayMetrics.widthPixels.toFloat()
    private val h get() = resources.displayMetrics.heightPixels.toFloat()

    fun scrollDown() = swipe(w / 2, h * 0.75f, w / 2, h * 0.25f)
    fun scrollUp() = swipe(w / 2, h * 0.25f, w / 2, h * 0.75f)
    fun swipeLeft() = swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
    fun swipeRight() = swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)

    /** Короткий список видимых элементов экрана (без полей паролей) — для подсказки Gemini. */
    fun screenSummary(): String {
        val root = rootInActiveWindow ?: return ""
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo) {
            if (out.size >= 50) return
            if (!n.isPassword) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty() && (n.isClickable || n.isEditable || n.childCount == 0)) out.add(t.take(40))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return "${root.packageName}: " + out.joinToString(" | ")
    }
}
