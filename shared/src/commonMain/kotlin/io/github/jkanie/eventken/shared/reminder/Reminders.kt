package io.github.jkanie.eventken.shared.reminder

import io.github.jkanie.eventken.shared.model.EventSummary
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** When to remind someone of a saved event. */
@Serializable
sealed interface ReminderRule {
    /** A fixed time before the start, such as 1 hour. Ignored for all-day events. */
    @Serializable
    data class BeforeStart(
        val minutes: Int,
    ) : ReminderRule

    /** The day before, at a time of day in the event's time zone. */
    @Serializable
    data class DayBefore(
        val at: LocalTime,
    ) : ReminderRule

    /** The day of the event, at a time of day in the event's time zone. */
    @Serializable
    data class MorningOf(
        val at: LocalTime,
    ) : ReminderRule
}

data class PlannedReminder(
    val eventId: Long,
    val at: Instant,
    val rule: ReminderRule,
)

object ReminderPlanner {
    /** The evening before at 18:00, plus 1 hour before the start. */
    val DEFAULT_RULES: List<ReminderRule> =
        listOf(ReminderRule.DayBefore(LocalTime(18, 0)), ReminderRule.BeforeStart(60))

    /** All-day events have no start time worth counting back from, so they get a morning reminder instead. */
    val ALL_DAY_FALLBACK: ReminderRule = ReminderRule.MorningOf(LocalTime(9, 0))

    /** Reminders closer together than this collapse into the earlier reminder. */
    val MIN_GAP: Duration = 5.minutes

    /**
     * Works out when to remind about [event]. A cancelled event gets none. Times that
     * are already past, or that fall at or after the start, are dropped, because a
     * reminder then helps nobody. The zone is the event's own when it has 1, so
     * "the day before at 18:00" means 18:00 where the event takes place.
     */
    fun plan(
        event: EventSummary,
        rules: List<ReminderRule> = DEFAULT_RULES,
        now: Instant,
        deviceZone: TimeZone,
    ): List<PlannedReminder> {
        if (event.cancelled) return emptyList()
        val zone = event.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: deviceZone
        val startDate = event.startsAt.toLocalDateTime(zone).date

        val effectiveRules =
            if (event.allDay) {
                rules.filterNot { it is ReminderRule.BeforeStart }.ifEmpty { listOf(ALL_DAY_FALLBACK) }
            } else {
                rules
            }

        val candidates =
            effectiveRules.map { rule ->
                val at =
                    when (rule) {
                        is ReminderRule.BeforeStart -> event.startsAt - rule.minutes.minutes
                        is ReminderRule.DayBefore -> startDate.minus(DatePeriod(days = 1)).atTime(rule.at).toInstant(zone)
                        is ReminderRule.MorningOf -> startDate.atTime(rule.at).toInstant(zone)
                    }
                PlannedReminder(event.id, at, rule)
            }

        // For an all-day event the start is midnight, so a reminder on the day itself is still useful.
        val latest = if (event.allDay) event.endsAt ?: (event.startsAt + 24.hours) else event.startsAt

        val kept = mutableListOf<PlannedReminder>()
        candidates
            .filter { it.at > now && it.at < latest }
            .sortedBy { it.at }
            .forEach { candidate ->
                val previous = kept.lastOrNull()
                if (previous == null || candidate.at - previous.at >= MIN_GAP) kept += candidate
            }
        return kept
    }
}
