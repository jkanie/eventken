package io.github.jkanie.eventken.server.ingest

import io.github.jkanie.eventken.server.support.Fixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class IcsParserTest {
    private val parser = IcsParser()
    private val arts by lazy { parser.parse(Fixtures.text("feeds/lantern-hill-arts.ics")).associateBy { it.externalId } }

    @Test
    fun readsEveryEventOfTheSampleFeed() {
        assertEquals(5, arts.size)
        assertEquals(3, parser.parse(Fixtures.text("feeds/saltwater-running-club.ics")).size)
    }

    @Test
    fun tzidTimesUnfoldedTextAndEscapes() {
        val concert = arts.getValue("harbour-lights-2027@lantern-hill.example")
        assertEquals("Harbour Lights Summer Concert", concert.title)
        // 19:30 PDT is 02:30 UTC the next day.
        assertEquals(Instant.parse("2027-07-11T02:30:00Z"), concert.startsAt)
        assertEquals(Instant.parse("2027-07-11T04:30:00Z"), concert.endsAt)
        assertEquals("America/Vancouver", concert.timeZone)
        assertEquals(
            "An evening of brass and strings on the harbour steps. Bring a blanket, the steps are cold after sunset.\nAll ages. Free entry.",
            concert.description,
        )
        assertEquals("Harbourside Hall, 100 Wharf Lane", concert.venue)
        assertEquals(48.4235, concert.latitude)
        assertEquals(-123.37, concert.longitude)
        assertEquals(listOf("music", "outdoor"), concert.categories)
        assertEquals("https://lantern-hill.example/events/harbour-lights", concert.url)
    }

    @Test
    fun keepsTheOrganiserNameButNoAddressesOrAttendees() {
        val concert = arts.getValue("harbour-lights-2027@lantern-hill.example")
        assertEquals("Lantern Hill Arts Collective", concert.organiser)
        val stored = concert.toJson()
        assertFalse("@" in stored.replace("@lantern-hill.example", ""), stored)
        assertFalse("attendee" in stored.lowercase())
        assertFalse("mailto" in stored)
    }

    @Test
    fun allDayDatesEndAtTheExclusiveDtend() {
        val open = arts.getValue("open-studio-2027@lantern-hill.example")
        assertTrue(open.allDay)
        assertEquals(Instant.parse("2027-07-17T07:00:00Z"), open.startsAt)
        assertEquals(Instant.parse("2027-07-18T07:00:00Z"), open.endsAt)
    }

    @Test
    fun cancelledStatus() {
        assertTrue(arts.getValue("poetry-lantern-2027@lantern-hill.example").cancelled)
        assertFalse(arts.getValue("open-studio-2027@lantern-hill.example").cancelled)
    }

    @Test
    fun floatingTimesUseTheCalendarZoneAndDurationGivesTheEnd() {
        val jazz = arts.getValue("jazz-park-2027@lantern-hill.example")
        assertEquals(Instant.parse("2027-07-25T01:00:00Z"), jazz.startsAt)
        assertEquals(Instant.parse("2027-07-25T03:00:00Z"), jazz.endsAt)
        assertNull(jazz.description, "the VALARM description must not leak into the event")
    }

    @Test
    fun recurringEventsGiveTheirFirstDate() {
        val pottery = arts.getValue("pottery-basics-2027@lantern-hill.example")
        assertEquals("Pottery Basics, Week 1", pottery.title)
        assertEquals(Instant.parse("2027-07-07T01:00:00Z"), pottery.startsAt)
        assertEquals(listOf("arts", "learning"), pottery.categories)
    }

    @Test
    fun utcTimesAndEscapedSemicolons() {
        val running = parser.parse(Fixtures.text("feeds/saltwater-running-club.ics")).associateBy { it.title }
        assertEquals(Instant.parse("2027-07-18T15:00:00Z"), running.getValue("Saltwater 10K").startsAt)
        assertEquals("8 km on forest trails; headlamps after 20:30.", running.getValue("Sunset Trail Run").description)
        assertEquals("Saltwater Running Club", running.getValue("Saltwater 10K").organiser)
    }

    @Test
    fun parsesQuotedParametersWithColons() {
        val property = IcsParser.parseLine("ORGANIZER;CN=\"Club: Tides; East\";ROLE=CHAIR:mailto:x@example.org")!!
        assertEquals("ORGANIZER", property.name)
        assertEquals(mapOf("CN" to "Club: Tides; East", "ROLE" to "CHAIR"), property.params)
        assertEquals("mailto:x@example.org", property.value)
    }

    @Test
    fun unfoldsTabsAndLoneLineFeeds() {
        assertEquals(listOf("SUMMARY:Long title", "UID:1"), IcsParser.unfold("SUMMARY:Long\n\t title\nUID:1\n"))
    }

    @Test
    fun recurrenceIdKeepsChangedInstancesApart() {
        val text =
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:weekly@example.org
            DTSTART:20270701T180000Z
            SUMMARY:Weekly Swim
            END:VEVENT
            BEGIN:VEVENT
            UID:weekly@example.org
            RECURRENCE-ID:20270708T180000Z
            DTSTART:20270708T190000Z
            SUMMARY:Weekly Swim (moved)
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        assertEquals(
            listOf("weekly@example.org", "weekly@example.org#20270708T180000Z"),
            parser.parse(text).map { it.externalId },
        )
    }

    @Test
    fun eventsWithoutUidGetAStableId() {
        val text = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:20270701T180000Z\nSUMMARY:No Id\nEND:VEVENT\nEND:VCALENDAR\n"
        val first = parser.parse(text).single().externalId
        assertTrue(first.startsWith("generated:"))
        assertEquals(first, parser.parse(text).single().externalId)
    }

    @Test
    fun skipsEventsWithoutStartOrTitleAndRefusesOtherFiles() {
        val text = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:No start\nEND:VEVENT\nBEGIN:VEVENT\nDTSTART:20270701\nEND:VEVENT\nEND:VCALENDAR"
        assertEquals(0, parser.parse(text).size)
        assertFailsWith<FeedParseException> { parser.parse("<html>not a calendar</html>") }
    }
}
