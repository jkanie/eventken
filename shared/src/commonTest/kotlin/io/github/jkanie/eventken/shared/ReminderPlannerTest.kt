package io.github.jkanie.eventken.shared

import io.github.jkanie.eventken.shared.model.EventSummary
import io.github.jkanie.eventken.shared.reminder.ReminderPlanner
import io.github.jkanie.eventken.shared.reminder.ReminderRule
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReminderPlannerTest {
    private val vancouver = TimeZone.of("America/Vancouver")

    // 19:30 in Victoria on 2026-07-10 (PDT, UTC-7).
    private val concert =
        EventSummary(
            id = 7,
            title = "Harbour Lights Concert",
            startsAt = Instant.parse("2026-07-11T02:30:00Z"),
            timeZone = "America/Vancouver",
        )

    @Test
    fun defaultRulesGiveTheEveningBeforeAndAnHourBefore() {
        val plan = ReminderPlanner.plan(concert, now = Instant.parse("2026-07-01T00:00:00Z"), deviceZone = TimeZone.UTC)
        assertEquals(
            listOf(Instant.parse("2026-07-10T01:00:00Z"), Instant.parse("2026-07-11T01:30:00Z")),
            plan.map { it.at },
        )
    }

    @Test
    fun theEventZoneWinsOverTheDeviceZone() {
        val plan =
            ReminderPlanner.plan(
                concert,
                rules = listOf(ReminderRule.DayBefore(LocalTime(18, 0))),
                now = Instant.parse("2026-07-01T00:00:00Z"),
                deviceZone = TimeZone.of("Asia/Tokyo"),
            )
        // 18:00 PDT on 2026-07-09 is 01:00 UTC on 2026-07-10.
        assertEquals(Instant.parse("2026-07-10T01:00:00Z"), plan.single().at)
    }

    @Test
    fun pastRemindersAreDropped() {
        val plan = ReminderPlanner.plan(concert, now = Instant.parse("2026-07-10T12:00:00Z"), deviceZone = vancouver)
        assertEquals(listOf(Instant.parse("2026-07-11T01:30:00Z")), plan.map { it.at })
    }

    @Test
    fun cancelledEventsGetNoReminders() {
        val plan = ReminderPlanner.plan(concert.copy(cancelled = true), now = Instant.parse("2026-07-01T00:00:00Z"), deviceZone = vancouver)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun allDayEventsSkipCountdownRulesAndFallBackToTheMorning() {
        val fair =
            EventSummary(
                id = 9,
                title = "Quadra Street Fair",
                startsAt = Instant.parse("2026-08-15T07:00:00Z"),
                endsAt = Instant.parse("2026-08-16T07:00:00Z"),
                allDay = true,
                timeZone = "America/Vancouver",
            )
        val plan =
            ReminderPlanner.plan(
                fair,
                rules = listOf(ReminderRule.BeforeStart(60)),
                now = Instant.parse("2026-08-01T00:00:00Z"),
                deviceZone = vancouver,
            )
        assertEquals(ReminderPlanner.ALL_DAY_FALLBACK, plan.single().rule)
        assertEquals(Instant.parse("2026-08-15T16:00:00Z"), plan.single().at)
    }

    @Test
    fun remindersAtOrAfterTheStartAreDropped() {
        val breakfast = concert.copy(startsAt = Instant.parse("2026-07-10T15:00:00Z"))
        val plan =
            ReminderPlanner.plan(
                breakfast,
                rules = listOf(ReminderRule.MorningOf(LocalTime(9, 0))),
                now = Instant.parse("2026-07-01T00:00:00Z"),
                deviceZone = vancouver,
            )
        assertTrue(plan.isEmpty(), "09:00 is after an 08:00 start")
    }

    @Test
    fun remindersCloseTogetherCollapse() {
        val plan =
            ReminderPlanner.plan(
                concert,
                rules = listOf(ReminderRule.BeforeStart(60), ReminderRule.BeforeStart(62), ReminderRule.BeforeStart(30)),
                now = Instant.parse("2026-07-01T00:00:00Z"),
                deviceZone = vancouver,
            )
        assertEquals(listOf(62, 30), plan.map { (it.rule as ReminderRule.BeforeStart).minutes })
    }
}
