package com.jayathu.automata.scripts

import android.content.Context
import android.util.Log
import com.jayathu.automata.data.model.DecisionMode
import com.jayathu.automata.data.model.RideApp
import com.jayathu.automata.data.model.TaskConfig
import com.jayathu.automata.engine.AutomationStep
import com.jayathu.automata.engine.StepResult
import com.jayathu.automata.service.AutomataAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OrchestratorResult(
    val pickMePrice: Double? = null,
    val uberPrice: Double? = null,
    val winner: RideApp? = null,
    val error: String? = null
)

class RideOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "RideOrchestrator"
    }

    private val _result = MutableStateFlow(OrchestratorResult())
    val result: StateFlow<OrchestratorResult> = _result.asStateFlow()

    /**
     * Builds a combined step list that:
     * 1. Reads prices from enabled apps
     * 2. Compares prices and determines the winner
     * 3. Re-opens the winning app and books the ride
     */
    fun buildSteps(config: TaskConfig): List<AutomationStep> {
        val steps = mutableListOf<AutomationStep>()

        // Phase 1: Read prices from both apps
        if (config.enablePickMe) {
            steps.addAll(PickMeScript.buildSteps(context, config.destinationAddress, config.rideType))
            steps.add(returnToHome())
        }

        if (config.enableUber) {
            steps.addAll(UberScript.buildSteps(context, config.destinationAddress, config.rideType))
            steps.add(returnToHome())
        }

        // Phase 2: Compare prices
        if (config.enablePickMe && config.enableUber) {
            steps.add(comparePrices(config.decisionMode))
        } else if (config.enablePickMe) {
            steps.add(setWinner(RideApp.PICKME))
        } else {
            steps.add(setWinner(RideApp.UBER))
        }

        // Phase 3: Book the winner
        // We add booking steps for BOTH apps but each step checks collectedData["winner"]
        // and skips if it's not the winner. This way we don't need dynamic step insertion.
        if (config.enablePickMe) {
            steps.addAll(buildConditionalBookingSteps(
                RideApp.PICKME,
                PickMeScript.buildBookingSteps(context, config.destinationAddress, config.rideType)
            ))
        }
        if (config.enableUber) {
            steps.addAll(buildConditionalBookingSteps(
                RideApp.UBER,
                UberScript.buildBookingSteps(context, config.destinationAddress, config.rideType)
            ))
        }

        return steps
    }

    /**
     * Wraps each booking step so it only runs if this app is the winner.
     * If not the winner, the step is skipped.
     */
    private fun buildConditionalBookingSteps(
        app: RideApp,
        bookingSteps: List<AutomationStep>
    ): List<AutomationStep> {
        return bookingSteps.map { step ->
            AutomationStep(
                name = step.name,
                waitCondition = step.waitCondition,
                timeoutMs = step.timeoutMs,
                delayAfterMs = step.delayAfterMs,
                delayBeforeMs = step.delayBeforeMs,
                maxRetries = step.maxRetries,
                action = { root, stepContext ->
                    val winner = stepContext.collectedData["winner"]
                    if (winner != app.displayName) {
                        Log.i(TAG, "Skipping '${step.name}' — winner is $winner, not ${app.displayName}")
                        StepResult.Skip("Not the winner")
                    } else {
                        step.action(root, stepContext)
                    }
                }
            )
        }
    }

    private fun returnToHome() = AutomationStep(
        name = "Return to home screen",
        waitCondition = { true },
        timeoutMs = 3_000,
        delayAfterMs = 1000,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
            if (service != null) {
                com.jayathu.automata.engine.ActionExecutor.pressHome(service)
                StepResult.Success
            } else {
                StepResult.Failure("Accessibility service not available")
            }
        }
    )

    private fun setWinner(app: RideApp) = AutomationStep(
        name = "Set winner: ${app.displayName}",
        waitCondition = { true },
        timeoutMs = 2_000,
        action = { _, stepContext ->
            stepContext.collectedData["winner"] = app.displayName
            Log.i(TAG, "Only ${app.displayName} enabled, setting as winner")
            StepResult.SuccessWithData("winner", app.displayName)
        }
    )

    private fun comparePrices(decisionMode: DecisionMode) = AutomationStep(
        name = "Compare prices",
        waitCondition = { true },
        timeoutMs = 2_000,
        action = { _, stepContext ->
            val pickMeRaw = stepContext.collectedData["pickme_price"]
            val uberRaw = stepContext.collectedData["uber_price"]

            val pickMePrice = pickMeRaw?.toDoubleOrNull()
            val uberPrice = uberRaw?.toDoubleOrNull()

            Log.i(TAG, "Price comparison - PickMe: $pickMePrice, Uber: $uberPrice")

            val winner = when {
                pickMePrice != null && uberPrice != null -> {
                    when (decisionMode) {
                        DecisionMode.CHEAPEST -> if (pickMePrice <= uberPrice) RideApp.PICKME else RideApp.UBER
                        DecisionMode.FASTEST -> RideApp.PICKME // Default to PickMe for fastest
                    }
                }
                pickMePrice != null -> RideApp.PICKME
                uberPrice != null -> RideApp.UBER
                else -> null
            }

            _result.value = OrchestratorResult(
                pickMePrice = pickMePrice,
                uberPrice = uberPrice,
                winner = winner
            )

            if (winner != null) {
                val winnerName = winner.displayName
                val savings = if (pickMePrice != null && uberPrice != null) {
                    val diff = kotlin.math.abs(pickMePrice - uberPrice)
                    " (save Rs ${String.format("%.0f", diff)})"
                } else ""

                Log.i(TAG, "Winner: $winnerName$savings — booking now")
                stepContext.collectedData["winner"] = winnerName
                StepResult.SuccessWithData("winner_summary", "$winnerName$savings")
            } else {
                StepResult.Failure("Could not determine prices from either app")
            }
        }
    )
}
