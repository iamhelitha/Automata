package com.jayathu.automata.engine

import android.app.ActivityManager
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
            // CLEAR_TASK + NEW_TASK clears the entire task stack, starting the app fresh
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            return true
        }

        /**
         * Bring an app to the foreground without clearing its task stack.
         * The app resumes exactly where it was left off.
         */
        fun bringToForeground(context: Context, packageName: String): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
            return true
        }

        /**
         * Force-close an app by killing its background processes and clearing its task.
         * This ensures the app starts from its home screen on next launch.
         */
        fun forceCloseApp(context: Context, packageName: String) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses(packageName)
                Log.i(TAG, "Killed background processes for $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to kill $packageName: ${e.message}")
            }
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
