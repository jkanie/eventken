package io.github.jkanie.eventken.server.ingest

import io.github.jkanie.eventken.server.support.Fixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class JsonLdParserTest {
    private val parser = JsonLdParser()
    private val hall by lazy {
        parser.parse(Fixtures.text("pages/harbourside-hall.html"), "https://harbourside-hall.example/whats-on").associateBy { it.title }
    }
    private val market by lazy {
        parser.parse(Fixtures.text("pages/maple-commons-market.html"), "https://maple-commons.example/").associateBy { it.title }
    }

    @Test
    fun readsEventsFromAGraphAndSkipsOtherTypes() {
        assertEquals(setOf("Harbour Lights Concert", "Late Laughs: Improv Night", "Stage Lighting 101 (Online)"), hall.keys)
    }

    @Test
    fun placeAddressGeoOrganiserAndCategoryFromType() {
        val concert = hall.getValue("Harbour Lights Concert")
        assertEquals("https://harbourside-hall.example/events/harbour-lights", concert.externalId)
        assertEquals(Instant.parse("2027-07-11T02:30:00Z"), concert.startsAt)
        assertEquals("Harbourside Hall", concert.venue)
        assertEquals("100 Wharf Lane, Victoria, BC, CA", concert.address)
        assertEquals(48.4236, concert.latitude)
        assertEquals(listOf("music"), concert.categories)
        assertEquals("Harbourside Hall Society", concert.organiser)
        // Relative URLs resolve against the page, HTML in descriptions becomes plain text.
        assertEquals("https://harbourside-hall.example/events/harbour-lights", concert.url)
        assertTrue(concert.description!!.startsWith("Brass and strings"))
        assertTrue("<p>" !in concert.description)
    }

    @Test
    fun coordinatesGivenAsStrings() {
        assertEquals(-123.3702, hall.getValue("Late Laughs: Improv Night").longitude)
    }

    @Test
    fun onlineEvents() {
        val online = hall.getValue("Stage Lighting 101 (Online)")
        assertEquals("Online", online.venue)
        assertNull(online.latitude)
        assertEquals(listOf("learning", "online"), online.categories)
    }

    @Test
    fun singleObjectsArraysAndBrokenBlocks() {
        assertEquals(
            setOf("Maple Commons Night Market", "Maple Commons Plant Swap", "Harvest Supper", "Saltwater 10K Road Race"),
            market.keys,
        )
        // An offset written without a colon (-0700) still parses.
        assertEquals(Instant.parse("2027-07-04T00:00:00Z"), market.getValue("Maple Commons Night Market").startsAt)
        assertEquals("Maple Commons Market Society", market.getValue("Maple Commons Night Market").organiser)
    }

    @Test
    fun dateOnlyEventsAreAllDayAndRunToTheEndOfTheirLastDay() {
        val swap = market.getValue("Maple Commons Plant Swap")
        assertTrue(swap.allDay)
        assertEquals(Instant.parse("2027-07-24T00:00:00Z"), swap.startsAt)
        assertEquals(Instant.parse("2027-07-25T00:00:00Z"), swap.endsAt)
        assertEquals(listOf("market"), swap.categories)
    }

    @Test
    fun cancelledStatus() {
        assertTrue(market.getValue("Harvest Supper").cancelled)
    }

    @Test
    fun timesWithoutOffsetUseTheDefaultZone() {
        val html =
            """<script type="application/ld+json">{"@type":["Event","Thing"],"name":"Local","startDate":"2027-07-01T19:00:00"}</script>"""
        val event = JsonLdParser(Fixtures.vancouver).parse(html, "https://example.org/").single()
        assertEquals(Instant.parse("2027-07-02T02:00:00Z"), event.startsAt)
        assertEquals("America/Vancouver", event.timeZone)
    }

    @Test
    fun pagesWithoutEventsGiveNothing() {
        assertEquals(0, parser.parse("<html><body>No data</body></html>", "https://example.org/").size)
    }
}
