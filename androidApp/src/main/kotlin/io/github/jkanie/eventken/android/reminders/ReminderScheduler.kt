package io.github.jkanie.eventken.android.reminders

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.jkanie.eventken.shared.format.EventText
import io.github.jkanie.eventken.shared.model.EventSummary
import io.github.jkanie.eventken.shared.reminder.PlannedReminder
import io.github.jkanie.eventken.shared.reminder.ReminderPlanner
import io.github.jkanie.eventken.shared.reminder.ReminderRule
import kotlinx.datetime.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Turns the shared reminder rules into WorkManager jobs. WorkManager keeps them
 * across restarts and reboots, so a reminder set today still fires next month.
 */
class ReminderScheduler(
    context: Context,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val workManager = WorkManager.getInstance(context)

    /** Replaces any reminders for [event] and returns the times it set. */
    fun schedule(
        event: EventSummary,
        rules: List<ReminderRule> = ReminderPlanner.DEFAULT_RULES,
    ): List<PlannedReminder> {
        cancel(event.id)
        val now = clock.now()
        val plan = ReminderPlanner.plan(event, rules, now, zone())
        plan.forEach { reminder ->
            val request =
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay((reminder.at - now).inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .setInputData(
                        workDataOf(
                            ReminderWorker.KEY_EVENT_ID to event.id,
                            ReminderWorker.KEY_TITLE to event.title,
                            ReminderWorker.KEY_TEXT to EventText.whenText(event, zone()),
                        ),
                    ).addTag(tag(event.id))
                    .build()
            workManager.enqueueUniqueWork(uniqueName(event.id, reminder.at), ExistingWorkPolicy.REPLACE, request)
        }
        return plan
    }

    fun cancel(eventId: Long) {
        workManager.cancelAllWorkByTag(tag(eventId))
    }

    companion object {
        fun tag(eventId: Long) = "reminder-event-$eventId"

        fun uniqueName(
            eventId: Long,
            at: Instant,
        ) = "reminder-$eventId-${at.toEpochMilliseconds()}"
    }
}
