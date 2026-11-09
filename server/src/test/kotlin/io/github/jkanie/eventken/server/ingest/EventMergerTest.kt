package io.github.jkanie.eventken.server.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class EventMergerTest {
    private val first =
        ParsedEvent(
            externalId = "ics",
            title = "Harbour Lights Summer Concert",
            startsAt = Instant.parse("2027-07-11T02:30:00Z"),
            description = "Short.",
            categories = listOf("music", "outdoor"),
            organiser = "Lantern Hill Arts Collective",
        )
    private val second =
        ParsedEvent(
            externalId = "jsonld",
            title = "Harbour Lights Concert",
            startsAt = Instant.parse("2027-07-11T02:35:00Z"),
            endsAt = Instant.parse("2027-07-11T04:30:00Z"),
            description = "A much longer description of the same evening.",
            venue = "Harbourside Hall",
            latitude = 48.4236,
            longitude = -123.3702,
            categories = listOf("music", "family"),
            organiser = "Harbourside Hall Society",
        )

    @Test
    fun firstSeenLeadsAndGapsFillFromLaterSources() {
        val merged = EventMerger.merge(listOf(first, second))
        assertEquals("Harbour Lights Summer Concert", merged.title)
        assertEquals(first.startsAt, merged.startsAt)
        assertEquals(second.endsAt, merged.endsAt)
        assertEquals("Harbourside Hall", merged.venue)
        assertEquals(48.4236, merged.latitude)
        assertEquals("Lantern Hill Arts Collective", merged.organiser)
    }

    @Test
    fun longestDescriptionAndAllCategories() {
        val merged = EventMerger.merge(listOf(first, second))
        assertEquals(second.description, merged.description)
        assertEquals(listOf("music", "outdoor", "family"), merged.categories)
    }

    @Test
    fun oneCancelledListingCancelsTheEvent() {
        assertTrue(EventMerger.merge(listOf(first, second.copy(cancelled = true))).cancelled)
    }

    @Test
    fun needsASource() {
        assertFailsWith<IllegalArgumentException> { EventMerger.merge(emptyList()) }
    }
}
