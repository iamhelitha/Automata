package com.jayathu.automata.engine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object UiInspector {

    private const val TAG = "UiInspector"
    private const val MAX_DEPTH = 15

    fun dumpCurrentScreen(root: AccessibilityNodeInfo?) {
        if (root == null) {
            Log.w(TAG, "No root node available")
            return
        }
        val packageName = root.packageName?.toString() ?: "unknown"
        Log.i(TAG, "=== UI TREE DUMP for $packageName ===")
        dumpNode(root, 0)
        Log.i(TAG, "=== END DUMP ===")
        Log.i(TAG, "")
        Log.i(TAG, "=== INTERACTIVE ELEMENTS WITH BOUNDS ===")
        dumpInteractiveWithBounds(root, 0)
        Log.i(TAG, "=== END INTERACTIVE ===")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_DEPTH) return

        val indent = "  ".repeat(depth)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.take(50) ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val desc = node.contentDescription?.toString()?.take(50) ?: ""
        val clickable = if (node.isClickable) " [CLICK]" else ""
        val scrollable = if (node.isScrollable) " [SCROLL]" else ""
        val editable = if (node.isEditable) " [EDIT]" else ""
        val focused = if (node.isFocused) " [FOCUS]" else ""

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val info = buildString {
            append("$indent$className")
            append(" [${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}]")
            if (text.isNotEmpty()) append(" text=\"$text\"")
            if (viewId.isNotEmpty()) append(" id=$viewId")
            if (desc.isNotEmpty()) append(" desc=\"$desc\"")
            append(clickable)
            append(scrollable)
            append(editable)
            append(focused)
        }

        Log.d(TAG, info)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
        }
    }

    private fun dumpInteractiveWithBounds(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_DEPTH) return

        if (node.isClickable || node.isEditable || node.isScrollable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()

            val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val text = node.text?.toString()?.take(80) ?: ""
            val desc = node.contentDescription?.toString()?.take(80) ?: ""

            val flags = buildString {
                if (node.isClickable) append("CLICK ")
                if (node.isEditable) append("EDIT ")
                if (node.isScrollable) append("SCROLL ")
            }.trim()

            Log.i(TAG, "  [$flags] $className bounds=[${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}] center=($centerX,$centerY) text=\"$text\" desc=\"$desc\"")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpInteractiveWithBounds(child, depth + 1)
        }
    }
}
