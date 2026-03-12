package com.jayathu.automata.engine

import android.view.accessibility.AccessibilityNodeInfo

object NodeFinder {

    fun findByText(root: AccessibilityNodeInfo?, text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) return null
        return if (exact) {
            nodes.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
        } else {
            nodes.firstOrNull()
        }
    }

    fun findByViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes?.firstOrNull()
    }

    fun findByClassName(root: AccessibilityNodeInfo?, className: String): AccessibilityNodeInfo? {
        if (root == null) return null
        return findNodeRecursive(root) { node ->
            node.className?.toString() == className
        }
    }

    fun findByContentDescription(root: AccessibilityNodeInfo?, description: String, exact: Boolean = false): AccessibilityNodeInfo? {
        if (root == null) return null
        return findNodeRecursive(root) { node ->
            val desc = node.contentDescription?.toString() ?: return@findNodeRecursive false
            if (exact) desc.equals(description, ignoreCase = true)
            else desc.contains(description, ignoreCase = true)
        }
    }

    fun findAllByText(root: AccessibilityNodeInfo?, text: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    fun findAllByViewId(root: AccessibilityNodeInfo?, viewId: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
    }

    fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    fun findNodeRecursive(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeRecursive(child, predicate)
            if (result != null) return result
        }
        return null
    }

    fun findAllNodesRecursive(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, predicate, results)
        return results
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (predicate(node)) results.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, predicate, results)
        }
    }

    fun hasNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (root == null) return false
        return findNodeRecursive(root, predicate) != null
    }

    fun dumpTree(root: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (root == null) return "null"
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        sb.appendLine("$indent${root.className} [text=${root.text}, id=${root.viewIdResourceName}, desc=${root.contentDescription}, click=${root.isClickable}]")
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            sb.append(dumpTree(child, depth + 1))
        }
        return sb.toString()
    }
}
