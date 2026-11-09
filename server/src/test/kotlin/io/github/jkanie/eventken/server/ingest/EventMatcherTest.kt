package io.github.jkanie.eventken.server.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EventMatcherTest {
    private val start = Instant.parse("2027-07-11T02:30:00Z")
    private val base =
        ParsedEvent(
            externalId = "a",
            title = "Harbour Lights Summer Concert",
            startsAt = start,
            venue = "Harbourside Hall",
            latitude = 48.4235,
            longitude = -123.3700,
        )

    @Test
    fun sameEventFromAnotherSource() {
        val other = base.copy(externalId = "b", title = "Harbour Lights Concert", latitude = 48.4236, longitude = -123.3702)
        assertNotNull(EventMatcher.score(base, other))
    }

    @Test
    fun startsTooFarApart() {
        assertNull(EventMatcher.score(base, base.copy(startsAt = start + 31.minutes)))
        assertNotNull(EventMatcher.score(base, base.copy(startsAt = start + 30.minutes)))
    }

    @Test
    fun sameTitleSameTimeButAcrossTown() {
        assertNull(EventMatcher.score(base, base.copy(latitude = 48.45, longitude = -123.30)))
    }

    @Test
    fun sameHallSameTimeDifferentShow() {
        assertNull(EventMatcher.score(base, base.copy(title = "Late Laughs: Improv Night")))
    }

    @Test
    fun venueNamesStandInForMissingCoordinates() {
        val a = base.copy(title = "Saltwater 10K", venue = "Driftwood Point Shelter")
        val b = a.copy(title = "Saltwater 10K Road Race", venue = "Driftwood Point", latitude = null, longitude = null)
        assertNotNull(EventMatcher.score(a, b))
        assertNull(EventMatcher.score(a, b.copy(venue = "Mossy Ridge trailhead")))
    }

    @Test
    fun withoutAnyPlaceTheTitlesMustAgreeClosely() {
        val a = base.copy(venue = null, latitude = null, longitude = null)
        assertNotNull(EventMatcher.score(a, a.copy(title = "Harbour Lights Summer Concert!")))
        // 2 of 3 words is enough with a place, not without 1.
        assertNull(EventMatcher.score(a, a.copy(title = "Harbour Lights Gala")))
    }

    @Test
    fun allDayNeverMatchesTimed() {
        assertNull(EventMatcher.score(base, base.copy(allDay = true)))
    }

    @Test
    fun tokensIgnoreCaseAccentsPunctuationAndSmallWords() {
        assertEquals(setOf("cafe", "concert", "harbour"), EventMatcher.tokens("The Café Concert at the HARBOUR!"))
    }

    @Test
    fun closerStartsScoreHigher() {
        val near = EventMatcher.score(base, base.copy(startsAt = start + 5.minutes))!!
        val far = EventMatcher.score(base, base.copy(startsAt = start + 25.minutes))!!
        assert(near > far)
    }
}
