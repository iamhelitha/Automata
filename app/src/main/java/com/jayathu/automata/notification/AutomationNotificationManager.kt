package com.jayathu.automata.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jayathu.automata.MainActivity
import com.jayathu.automata.R
import com.jayathu.automata.engine.AutomationState

class AutomationNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "automata_status"
        private const val RESULT_CHANNEL_ID = "automata_result"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automation Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the current automation progress"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)

        val resultChannel = NotificationChannel(
            RESULT_CHANNEL_ID,
            "Automation Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows price comparison results"
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(resultChannel)
    }

    fun updateFromState(state: AutomationState) {
        when (state) {
            is AutomationState.Idle -> dismiss()
            is AutomationState.Running -> show(
                "Running: ${state.stepName}",
                "Step ${state.stepIndex + 1} of ${state.totalSteps}",
                ongoing = true,
                progress = Pair(state.stepIndex, state.totalSteps)
            )
            is AutomationState.WaitingForUI -> show(
                "Waiting: ${state.stepName}",
                "Looking for UI elements...",
                ongoing = true
            )
            is AutomationState.StepComplete -> show(
                "Completed: ${state.stepName}",
                "Step ${state.stepIndex + 1} of ${state.totalSteps} done",
                ongoing = true,
                progress = Pair(state.stepIndex + 1, state.totalSteps)
            )
            is AutomationState.Error -> show(
                "Error: ${state.stepName}",
                state.reason,
                ongoing = false
            )
            is AutomationState.Done -> {
                dismiss() // Remove progress notification
                showResult(state.collectedData)
            }
            is AutomationState.Aborted -> show(
                "Automation Aborted",
                "The automation was stopped",
                ongoing = false
            )
        }
    }

    private fun show(
        title: String,
        text: String,
        ongoing: Boolean,
        progress: Pair<Int, Int>? = null
    ) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setSilent(true)

        if (progress != null) {
            builder.setProgress(progress.second, progress.first, false)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun showResult(data: Map<String, String>) {
        val pickMePrice = data["pickme_price"]
        val uberPrice = data["uber_price"]
        val winner = data["winner"]

        val title = if (winner != null) {
            "Winner: $winner"
        } else {
            "Automation Complete"
        }

        val lines = mutableListOf<String>()
        if (pickMePrice != null) lines.add("PickMe: Rs $pickMePrice")
        if (uberPrice != null) lines.add("Uber: Rs $uberPrice")

        val pickMe = pickMePrice?.toDoubleOrNull()
        val uber = uberPrice?.toDoubleOrNull()
        if (pickMe != null && uber != null) {
            val savings = kotlin.math.abs(pickMe - uber)
            lines.add("You save Rs ${String.format("%.0f", savings)}")
        }

        val summary = if (lines.isEmpty()) "Done" else lines.joinToString("\n")

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "Done")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }
}
