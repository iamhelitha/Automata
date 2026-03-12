package com.jayathu.automata.engine

import android.view.accessibility.AccessibilityNodeInfo

data class AutomationStep(
    val name: String,
    val waitCondition: (root: AccessibilityNodeInfo) -> Boolean,
    val action: suspend (root: AccessibilityNodeInfo, context: StepContext) -> StepResult,
    val timeoutMs: Long = 10_000,
    val maxRetries: Int = 2,
    val delayBeforeMs: Long = 0,
    val delayAfterMs: Long = 300
)

data class StepContext(
    var stepIndex: Int,
    val totalSteps: Int,
    val collectedData: MutableMap<String, String> = mutableMapOf()
)

sealed class StepResult {
    data object Success : StepResult()
    data class SuccessWithData(val key: String, val value: String) : StepResult()
    data class Retry(val reason: String) : StepResult()
    data class Failure(val reason: String) : StepResult()
    data class Skip(val reason: String) : StepResult()
}
