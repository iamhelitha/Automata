package com.jayathu.automata.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jayathu.automata.engine.AutomationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutomataAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomataA11y"

        private val _instance = MutableStateFlow<AutomataAccessibilityService?>(null)
        val instance: StateFlow<AutomataAccessibilityService?> = _instance.asStateFlow()

        val isRunning: Boolean
            get() = _instance.value != null
    }

    private var engine: AutomationEngine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        engine = AutomationEngine(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        engine?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        engine?.shutdown()
        engine = null
        _instance.value = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    fun getEngine(): AutomationEngine? = engine

    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow
}
