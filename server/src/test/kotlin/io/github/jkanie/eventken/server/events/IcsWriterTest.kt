package io.github.jkanie.eventken.server.events

import io.github.jkanie.eventken.server.ingest.IcsParser
import io.github.jkanie.eventken.server.support.FixedClock
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.EventSummary
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class IcsWriterTest {
    private val now = Instant.parse("2027-07-01T00:00:00Z")
    private val parser = IcsParser(clock = FixedClock(now))

    private val concert =
        EventDetail(
            summary =
                EventSummary(
                    id = 7,
                    title = "Harbour concert; strings, brass",
                    startsAt = Instant.parse("2027-07-12T02:30:00Z"),
                    endsAt = Instant.parse("2027-07-12T04:00:00Z"),
                    timeZone = "America/Vancouver",
                    venue = "Harbourside Hall",
                    address = "1 Wharf St, Victoria",
                    latitude = 48.4236,
                    longitude = -123.3702,
                    categories = listOf("music", "outdoor, free"),
                    url = "https://example.org/concert",
                ),
            description = "Bring a chair.\nDoors at 7 \\ seats are free.",
            organiser = "Lantern Hill Arts",
        )

    @Test
    fun aTimedEventReadsBackTheSame() {
        val text = IcsWriter.write(concert, "events.example.org", now)
        val event = parser.parse(text).single()
        assertEquals("event-7@events.example.org", event.externalId)
        assertEquals("Harbour concert; strings, brass", event.title)
        assertEquals(concert.summary.startsAt, event.startsAt)
        assertEquals(concert.summary.endsAt, event.endsAt)
        assertEquals(false, event.allDay)
        assertEquals("Bring a chair.\nDoors at 7 \\ seats are free.", event.description)
        assertEquals("Harbourside Hall, 1 Wharf St, Victoria", event.venue)
        assertEquals(48.4236, event.latitude)
        assertEquals(-123.3702, event.longitude)
        assertEquals(listOf("music", "outdoor, free"), event.categories)
        assertEquals("https://example.org/concert", event.url)
        assertEquals(false, event.cancelled)
        assertTrue("DTSTART:20270712T023000Z\r\n" in text)
        assertTrue("DTSTAMP:20270701T000000Z\r\n" in text)
        // The organiser's name has no address to go with it, so it is left out.
        assertTrue("ORGANIZER" !in text)
    }

    @Test
    fun anAllDayEventIsWrittenAsDatesInItsOwnZone() {
        // An all-day event on 12 July in Vancouver starts at 07:00 UTC.
        val market =
            EventDetail(
                EventSummary(
                    id = 9,
                    title = "Maple Commons market",
                    startsAt = Instant.parse("2027-07-12T07:00:00Z"),
                    endsAt = Instant.parse("2027-07-14T07:00:00Z"),
                    allDay = true,
                    timeZone = "America/Vancouver",
                    cancelled = true,
                ),
            )
        val text = IcsWriter.write(market, "localhost", now)
        assertTrue("X-WR-TIMEZONE:America/Vancouver\r\n" in text)
        assertTrue("DTSTART;VALUE=DATE:20270712\r\nDTEND;VALUE=DATE:20270714\r\n" in text)
        assertTrue("STATUS:CANCELLED\r\n" in text)
        val event = parser.parse(text).single()
        assertEquals(true, event.allDay)
        assertEquals(market.summary.startsAt, event.startsAt)
        assertEquals(market.summary.endsAt, event.endsAt)
        assertEquals(true, event.cancelled)
    }

    @Test
    fun linesEndInCrlfAndFoldAt75OctetsWithoutSplittingACharacter() {
        val long = EventDetail(concert.summary.copy(title = "Café ".repeat(40).trim()), description = "€".repeat(60))
        val text = IcsWriter.write(long, "localhost", now)
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        val lines = text.removeSuffix("\r\n").split("\r\n")
        assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size <= 75 }, lines.joinToString("\n"))
        assertTrue(lines.none { '\n' in it || '\r' in it })
        assertTrue(lines.count { it.startsWith(" ") } >= 4)
        val event = parser.parse(text).single()
        assertEquals("Café ".repeat(40).trim(), event.title)
        assertEquals("€".repeat(60), event.description)
    }

    @Test
    fun escapesTheFourSpecialCharacters() {
        assertEquals("a\\\\b\\;c\\,d\\ne\\nf", IcsWriter.escape("a\\b;c,d\ne\r\nf"))
        assertEquals("plain: text", IcsWriter.escape("plain: text"))
    }

    @Test
    fun severalEventsGoInOneCalendar() {
        val market =
            EventDetail(
                EventSummary(
                    id = 9,
                    title = "Maple Commons market",
                    startsAt = Instant.parse("2027-07-12T07:00:00Z"),
                    endsAt = Instant.parse("2027-07-13T07:00:00Z"),
                    allDay = true,
                    timeZone = "America/Vancouver",
                ),
            )
        val text = IcsWriter.writeAll(listOf(concert, market), "localhost", now)
        assertEquals(1, Regex("BEGIN:VCALENDAR").findAll(text).count())
        assertTrue("X-WR-TIMEZONE:America/Vancouver\r\n" in text)
        val events = parser.parse(text)
        assertEquals(listOf("event-7@localhost", "event-9@localhost"), events.map { it.externalId })
        assertEquals(market.summary.startsAt, events[1].startsAt)

        val mixed =
            IcsWriter.writeAll(
                listOf(concert, market.copy(summary = market.summary.copy(timeZone = "Europe/Paris"))),
                "localhost",
                now,
            )
        assertTrue("X-WR-TIMEZONE" !in mixed)
        assertTrue("DTSTART;VALUE=DATE:20270712\r\n" in mixed)

        val empty = IcsWriter.writeAll(emptyList(), "localhost", now)
        assertTrue(empty.startsWith("BEGIN:VCALENDAR\r\n") && empty.endsWith("END:VCALENDAR\r\n"))
        assertTrue("BEGIN:VEVENT" !in empty)
    }
}
