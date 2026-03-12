package com.jayathu.automata.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.jayathu.automata.service.AutomataAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.view.accessibility.AccessibilityEvent

class AutomationEngine(private val service: AutomataAccessibilityService) {

    companion object {
        private const val TAG = "AutomationEngine"

        fun isAccessibilityEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(context.packageName)
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun launchApp(context: Context, packageName: String): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            return true
        }

        fun isAppInstalled(context: Context, packageName: String): Boolean {
            return try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    private val sequencer = StepSequencer(
        rootProvider = { service.getRootNode() },
        onBackOut = {
            Log.i(TAG, "Executing back-out: pressing back twice then going home")
            ActionExecutor.pressBack(service)
            kotlinx.coroutines.delay(300)
            ActionExecutor.pressBack(service)
            kotlinx.coroutines.delay(300)
            ActionExecutor.pressHome(service)
        }
    )

    val state: StateFlow<AutomationState> = sequencer.state

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Events are consumed by the step sequencer's polling loop.
        // This hook is available for future use (e.g., detecting unexpected dialogs).
    }

    fun runAutomation(steps: List<AutomationStep>, onComplete: (AutomationResult) -> Unit) {
        currentJob?.cancel()
        sequencer.reset()

        currentJob = scope.launch {
            val result = sequencer.execute(steps)
            onComplete(result)
        }
    }

    fun abort() {
        sequencer.abort()
        currentJob?.cancel()
    }

    fun shutdown() {
        abort()
        scope.cancel()
    }
}
