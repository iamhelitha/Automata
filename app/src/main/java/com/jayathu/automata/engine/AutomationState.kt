package com.jayathu.automata.engine

sealed class AutomationState {
    data object Idle : AutomationState()
    data class Running(val stepName: String, val stepIndex: Int, val totalSteps: Int) : AutomationState()
    data class WaitingForUI(val stepName: String, val elapsedMs: Long, val timeoutMs: Long) : AutomationState()
    data class StepComplete(val stepName: String, val stepIndex: Int, val totalSteps: Int) : AutomationState()
    data class Error(val stepName: String, val reason: String, val recoverable: Boolean) : AutomationState()
    data class Done(val collectedData: Map<String, String>) : AutomationState()
    data object Aborted : AutomationState()
}
