package io.github.kkwans.nasfilebrowser

import android.app.Instrumentation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Values only: no cached UiObject2 or live accessibility node escapes a sample. */
internal data class FreshAccessibilityNodeBounds(
    val text: String?,
    val description: String?,
    val className: String?,
    val bounds: Rect,
    val visible: Boolean,
    val enabled: Boolean,
    val canLongClick: Boolean,
    val ancestorDescriptions: List<String>,
    val checkable: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
    val clickable: Boolean,
)

/** A stale/incomplete tree or changed active window invalidates the whole sample.
 * Callers keep their original retry budget; missing nodes never become a pass.
 */
internal fun freshAccessibilityBounds(instrumentation: Instrumentation, screen: Rect): List<FreshAccessibilityNodeBounds>? {
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
    val packageName = instrumentation.targetContext.packageName
    val result = mutableListOf<FreshAccessibilityNodeBounds>()
    var windowId = -1
    fun collect(node: AccessibilityNodeInfo, clip: Rect, ancestors: List<String>, longClick: Boolean, top: Boolean = false): Boolean {
        try {
            if (!node.refresh()) return false
            if (top) {
                if (node.packageName?.toString() != packageName || node.windowId < 0) return false
                windowId = node.windowId
            } else if (node.windowId != windowId) return false
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.intersect(clip)) bounds.setEmpty()
            val visible = node.isVisibleToUser && !bounds.isEmpty
            val enabled = node.isEnabled
            val description = node.contentDescription?.toString()
            val canLongClick = longClick || (visible && enabled && node.isLongClickable)
            result += FreshAccessibilityNodeBounds(node.text?.toString(), description, node.className?.toString(),
                bounds, visible, enabled, canLongClick, ancestors, node.isCheckable, node.isChecked, node.isScrollable, node.isClickable)
            val childClip = if (node.isScrollable) bounds else clip
            val childAncestors = if (description.isNullOrEmpty()) ancestors else ancestors + description
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: return false
                if (!collect(child, childClip, childAncestors, canLongClick)) return false
            }
            return true
        } finally { node.recycle() }
    }
    if (!collect(root, screen, emptyList(), false, top = true)) return null
    val active = instrumentation.uiAutomation.rootInActiveWindow ?: return null
    return try {
        if (active.refresh() && active.windowId == windowId && active.packageName?.toString() == packageName) result else null
    } finally { active.recycle() }
}
