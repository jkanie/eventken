package io.github.jkanie.eventken.server.ingest

import io.github.jkanie.eventken.server.support.FixedClock
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RecurrenceTest {
    private val vancouver = ZoneId.of("America/Vancouver")

    private fun calendar(vararg events: String) =
        "BEGIN:VCALENDAR\n" + events.joinToString("") { "BEGIN:VEVENT\n${it.trimIndent()}\nEND:VEVENT\n" } + "END:VCALENDAR\n"

    private fun parse(
        now: String,
        text: String,
    ) = IcsParser(clock = FixedClock(Instant.parse(now))).parse(text)

    private fun dates(
        rule: String,
        start: String,
        until: String = "2030-01-01",
    ): List<String> {
        val first = ZonedDateTime.of(LocalDate.parse(start), LocalTime.of(9, 0), vancouver)
        val horizon = ZonedDateTime.of(LocalDate.parse(until), LocalTime.MIDNIGHT, vancouver)
        return RecurrenceRule
            .parse(rule)!!
            .occurrences(first, horizon)
            .take(50)
            .map { it.toLocalDate().toString() }
            .toList()
    }

    @Test
    fun weeklyClassWithACancelledWeekAMovedWeekAndADaylightSavingChange() {
        // Tuesdays at 18:00 in Los Angeles, where daylight saving time ends on Sunday 2027-11-07. (Vancouver
        // is not used here: British Columbia stays on UTC-7 all year, so its clocks do not change.)
        val text =
            calendar(
                """
                UID:clay@example.org
                DTSTART;TZID=America/Los_Angeles:20271019T180000
                DTEND;TZID=America/Los_Angeles:20271019T200000
                RRULE:FREQ=WEEKLY;COUNT=6
                EXDATE;TZID=America/Los_Angeles:20271026T180000
                SUMMARY:Clay Night
                """,
                """
                UID:clay@example.org
                RECURRENCE-ID;TZID=America/Los_Angeles:20271102T180000
                DTSTART;TZID=America/Los_Angeles:20271104T190000
                DTEND;TZID=America/Los_Angeles:20271104T210000
                SUMMARY:Clay Night (moved to Thursday)
                """,
            )
        val events = parse("2027-10-01T00:00:00Z", text)
        assertEquals(
            listOf(
                // 18:00 PDT is 01:00 UTC the next day, 18:00 PST is 02:00 UTC.
                "clay@example.org#20271020T010000Z" to "2027-10-20T01:00:00Z",
                "clay@example.org#20271110T020000Z" to "2027-11-10T02:00:00Z",
                "clay@example.org#20271117T020000Z" to "2027-11-17T02:00:00Z",
                "clay@example.org#20271124T020000Z" to "2027-11-24T02:00:00Z",
                // The moved week keeps the id of the week it replaces, with its own new time.
                "clay@example.org#20271103T010000Z" to "2027-11-05T02:00:00Z",
            ),
            events.map { it.externalId to it.startsAt.toString() },
        )
        assertEquals("Clay Night (moved to Thursday)", events.last().title)
        // Every occurrence keeps the 2-hour length across the change.
        assertTrue(events.all { it.endsAt!! - it.startsAt == kotlin.time.Duration.parse("2h") })
    }

    @Test
    fun aCancelledInstanceStaysListedAsCancelled() {
        val text =
            calendar(
                """
                UID:run@example.org
                DTSTART:20270705T150000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Monday Run
                """,
                """
                UID:run@example.org
                RECURRENCE-ID:20270712T150000Z
                DTSTART:20270712T150000Z
                STATUS:CANCELLED
                SUMMARY:Monday Run
                """,
            )
        val events = parse("2027-07-01T00:00:00Z", text).associateBy { it.externalId }
        assertEquals(3, events.size)
        assertTrue(events.getValue("run@example.org#20270712T150000Z").cancelled)
        assertTrue(!events.getValue("run@example.org#20270719T150000Z").cancelled)
    }

    @Test
    fun onlyOccurrencesThatHaveNotEndedAndStartWithin12MonthsAreKept() {
        val text =
            calendar(
                """
                UID:market@example.org
                DTSTART;TZID=America/Vancouver:20270103T100000
                DTEND;TZID=America/Vancouver:20270103T140000
                RRULE:FREQ=MONTHLY
                SUMMARY:Sunday Market
                """,
            )
        val events = parse("2027-07-03T19:00:00Z", text)
        // Vancouver is on UTC-7. The January to June dates have ended. July 3 10:00 to 14:00 ends at 21:00 UTC,
        // after "now", so it is kept. The window ends 12 months after "now", at 2028-07-03 19:00 UTC, which keeps
        // the 2028-07-03 market (17:00 UTC) and none after it.
        assertEquals("market@example.org#20270703T170000Z", events.first().externalId)
        assertEquals(13, events.size)
        assertEquals(Instant.parse("2028-07-03T17:00:00Z"), events.last().startsAt)
    }

    @Test
    fun allDayEventsUseTheDateAsId() {
        val text =
            calendar(
                """
                UID:fair@example.org
                DTSTART;VALUE=DATE:20270710
                RRULE:FREQ=DAILY;COUNT=3
                EXDATE;VALUE=DATE:20270711
                SUMMARY:Summer Fair
                """,
            )
        assertEquals(
            listOf("fair@example.org#20270710", "fair@example.org#20270712"),
            parse("2027-07-01T00:00:00Z", text).map { it.externalId },
        )
    }

    @Test
    fun rdateAddsDatesAndAnUnexpandedRuleKeepsTheFirstDate() {
        val text =
            calendar(
                """
                UID:talk@example.org
                DTSTART:20270801T170000Z
                RDATE:20270815T170000Z,20270901T170000Z
                SUMMARY:Library Talk
                """,
                """
                UID:odd@example.org
                DTSTART:20270801T170000Z
                RRULE:FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR
                SUMMARY:Last Weekday Meetup
                """,
            )
        assertEquals(
            listOf(
                "talk@example.org#20270801T170000Z",
                "talk@example.org#20270815T170000Z",
                "talk@example.org#20270901T170000Z",
                "odd@example.org",
            ),
            parse("2027-07-01T00:00:00Z", text).map { it.externalId },
        )
    }

    @Test
    fun monthlyRules() {
        // 2nd Tuesday of each month.
        assertEquals(listOf("2027-07-13", "2027-08-10", "2027-09-14"), dates("FREQ=MONTHLY;BYDAY=2TU;COUNT=3", "2027-07-13"))
        // Last Friday of each month.
        assertEquals(listOf("2027-07-30", "2027-08-27", "2027-09-24"), dates("FREQ=MONTHLY;BYDAY=-1FR;COUNT=3", "2027-07-30"))
        // The 31st skips months that have no 31st.
        assertEquals(listOf("2027-01-31", "2027-03-31", "2027-05-31"), dates("FREQ=MONTHLY;COUNT=3", "2027-01-31"))
        // Every 2nd month on the 1st and the last day.
        assertEquals(
            listOf("2027-01-01", "2027-01-31", "2027-03-01", "2027-03-31"),
            dates("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=1,-1;COUNT=4", "2027-01-01"),
        )
    }

    @Test
    fun weeklyDailyYearlyRules() {
        // Every 2nd week on Monday and Wednesday, until a date (inclusive).
        assertEquals(
            listOf("2027-07-05", "2027-07-07", "2027-07-19", "2027-07-21", "2027-08-02"),
            dates("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270802", "2027-07-05"),
        )
        // UNTIL as a UTC time: 09:00 in Vancouver (UTC-7) on 2027-07-07 is 16:00 UTC, so that day is still in.
        assertEquals(listOf("2027-07-05", "2027-07-06", "2027-07-07"), dates("FREQ=DAILY;UNTIL=20270707T160000Z", "2027-07-05"))
        assertEquals(listOf("2027-07-05", "2027-07-06"), dates("FREQ=DAILY;UNTIL=20270707T155959Z", "2027-07-05"))
        // Weekdays only, from a daily rule.
        assertEquals(
            listOf("2027-07-09", "2027-07-12", "2027-07-13"),
            dates("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR;COUNT=3", "2027-07-09"),
        )
        // February 29 happens only in leap years.
        assertEquals(listOf("2028-02-29", "2032-02-29"), dates("FREQ=YEARLY;COUNT=2", "2028-02-29", until = "2040-01-01"))
        // Thanksgiving in Canada: the 2nd Monday of October.
        assertEquals(
            listOf("2027-10-11", "2028-10-09"),
            dates("FREQ=YEARLY;BYMONTH=10;BYDAY=2MO;COUNT=2", "2027-10-11"),
        )
        // The week starts on Sunday: every 2nd week, Sunday and Tuesday.
        assertEquals(
            listOf("2027-07-04", "2027-07-06", "2027-07-18"),
            dates("FREQ=WEEKLY;INTERVAL=2;WKST=SU;BYDAY=SU,TU;COUNT=3", "2027-07-04"),
        )
        // The horizon stops a rule with no end.
        assertEquals(3, dates("FREQ=WEEKLY", "2027-07-05", until = "2027-07-20").size)
    }

    @Test
    fun rulePartsThatAreNotExpandedOrAreMalformed() {
        for (rule in listOf(
            "FREQ=HOURLY",
            "FREQ=MONTHLY;BYSETPOS=1;BYDAY=MO",
            "FREQ=WEEKLY;BYDAY=2MO",
            "FREQ=YEARLY;BYDAY=20MO",
            "FREQ=DAILY;COUNT=0",
            "FREQ=DAILY;COUNT=2;UNTIL=20270101",
            "FREQ=DAILY;INTERVAL=0",
            "FREQ=WEEKLY;BYDAY=XX",
            "INTERVAL=2",
        )) {
            assertNull(RecurrenceRule.parse(rule), rule)
        }
        val rule = RecurrenceRule.parse("freq=weekly;byday=tu,th;wkst=su")!!
        assertEquals(DayOfWeek.SUNDAY, rule.weekStart)
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), rule.byDay.map { it.day })
    }
}
