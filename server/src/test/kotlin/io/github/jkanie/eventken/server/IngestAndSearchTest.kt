package io.github.jkanie.eventken.server

import io.github.jkanie.eventken.server.dev.seed
import io.github.jkanie.eventken.server.support.FixedClock
import io.github.jkanie.eventken.server.support.Fixtures
import io.github.jkanie.eventken.server.support.TestDatabase
import io.github.jkanie.eventken.shared.geo.Geo
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.github.jkanie.eventken.shared.search.SortOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Ingest, merging, search against a real PostgreSQL, with the sample feeds. */
class IngestAndSearchTest {
    private val clock = FixedClock(Instant.parse("2027-07-01T00:00:00Z"))
    private val services = Services.create(ServerConfig(), TestDatabase.dataSource, clock)

    // Harbourside Hall in the sample data.
    private val hallLat = 48.4236
    private val hallLon = -123.3702

    @BeforeEach
    fun seedFixtures() =
        runBlocking {
            TestDatabase.reset()
            seed(services, Fixtures.dir)
            Unit
        }

    private fun search(query: SearchQuery) = runBlocking { services.events.search(query.validated(), clock.now()) }

    private fun titles(query: SearchQuery) = search(query).items.map { it.title }

    @Test
    fun theSameEventFromAnIcsFeedAndAJsonLdPageIsStoredOnce() =
        runBlocking {
            val lines = seed(services, Fixtures.dir)
            assertEquals(listOf(5, 3, 3, 4), lines.map { it.parsed }, "the second seed parses the same files")
            assertTrue(lines.all { it.result.created == 0 && it.result.merged == 0 && it.result.updated == 0 }, "and changes nothing")

            TestDatabase.reset()
            val fresh = seed(services, Fixtures.dir)
            assertEquals(listOf(5, 3, 2, 3), fresh.map { it.result.created })
            assertEquals(listOf(0, 0, 1, 1), fresh.map { it.result.merged })
            assertEquals(11, search(SearchQuery()).total, "13 events, 2 of them cancelled")
            assertEquals(13, search(SearchQuery(includeCancelled = true)).total)
        }

    @Test
    fun mergedEventsTakeTheBestOfEachSource() =
        runBlocking {
            val concert = search(SearchQuery(text = "concert")).items.single()
            assertEquals("Harbour Lights Summer Concert", concert.title)
            assertEquals(2, concert.sourceCount)

            val detail = services.events.find(concert.id)!!
            assertEquals(2, detail.sources.size)
            assertEquals("Lantern Hill Arts Collective", detail.organiser)
            assertTrue(detail.description!!.startsWith("Brass and strings"), "the longer description wins")
            assertEquals("100 Wharf Lane, Victoria, BC, CA", detail.summary.address)
            assertEquals(listOf("music", "outdoor"), detail.summary.categories)

            val race = search(SearchQuery(text = "10K")).items.single()
            assertEquals("Saltwater 10K", race.title)
            assertEquals(2, race.sourceCount)
        }

    @Test
    fun distanceSearchMatchesTheHaversineFormula() {
        val near = search(SearchQuery(latitude = hallLat, longitude = hallLon, radiusKm = 1.0, sort = SortOrder.DISTANCE))
        assertEquals(listOf("Late Laughs: Improv Night", "Harbour Lights Summer Concert"), near.items.map { it.title })

        val wider = search(SearchQuery(latitude = hallLat, longitude = hallLon, radiusKm = 2.0, sort = SortOrder.DISTANCE, limit = 100))
        assertEquals(5, wider.total)
        wider.items.forEach { event ->
            val expected = Geo.haversineKm(hallLat, hallLon, event.latitude!!, event.longitude!!)
            assertEquals(expected, event.distanceKm!!, 0.01, event.title)
            assertTrue(event.distanceKm!! <= 2.0)
        }
        assertEquals(wider.items.map { it.distanceKm }, wider.items.map { it.distanceKm }.sortedBy { it })
    }

    @Test
    fun aLocationWithoutRadiusAddsDistancesButKeepsEveryEvent() {
        val page = search(SearchQuery(latitude = hallLat, longitude = hallLon, sort = SortOrder.DISTANCE, limit = 100))
        assertEquals(11, page.total)
        assertNull(page.items.last().distanceKm, "events without a place come last")
        assertNotNull(page.items.first().distanceKm)
    }

    @Test
    fun categoryDateAndCancelledFilters() {
        assertEquals(
            listOf("Beginner Running Clinic", "Saltwater 10K", "Sunset Trail Run"),
            titles(SearchQuery(categories = listOf("sport"))),
        )
        assertEquals(
            listOf("Harbour Lights Summer Concert"),
            titles(SearchQuery(from = Instant.parse("2027-07-10T00:00:00Z"), to = Instant.parse("2027-07-12T00:00:00Z"))),
        )
        assertTrue("Harvest Supper" !in titles(SearchQuery(limit = 100)))
        assertTrue("Harvest Supper" in titles(SearchQuery(limit = 100, includeCancelled = true)))
    }

    @Test
    fun eventsThatEndedAreHiddenUnlessFromSaysOtherwise() {
        clock.now = Instant.parse("2027-07-20T00:00:00Z")
        val upcoming = titles(SearchQuery(limit = 100))
        assertTrue("Harbour Lights Summer Concert" !in upcoming)
        assertTrue("Stage Lighting 101 (Online)" in upcoming)
        assertTrue("Harbour Lights Summer Concert" in titles(SearchQuery(from = Instant.parse("2027-07-01T00:00:00Z"), limit = 100)))
    }

    @Test
    fun relevanceRanksTitleMatchesFirstAndWebSyntaxWorks() {
        assertEquals("Maple Commons Night Market", titles(SearchQuery(text = "market")).first())
        assertEquals(listOf("Sunset Trail Run"), titles(SearchQuery(text = "trail -beginner")))
        assertEquals(listOf("Harbour Lights Summer Concert"), titles(SearchQuery(text = "\"lantern walk\"")))
    }

    @Test
    fun pagesCountTheWholeResult() {
        val page = search(SearchQuery(sort = SortOrder.START, limit = 5, offset = 10))
        assertEquals(11, page.total)
        assertEquals(1, page.items.size)
        assertEquals("Maple Commons Night Market", titles(SearchQuery(sort = SortOrder.START)).first())
    }

    @Test
    fun categoriesOfUpcomingEvents() =
        runBlocking {
            val counts = services.events.categories(clock.now()).associate { it.category to it.events }
            assertEquals(4, counts["outdoor"])
            assertEquals(3, counts["sport"])
            assertNull(counts["books"], "the only books event is cancelled")
        }

    @Test
    fun aListingThatLeavesItsFeedIsRemoved() =
        runBlocking {
            val feed = services.feeds.list().first { it.url.endsWith("lantern-hill-arts.ics") }
            val events = services.ingest.parse(feed, Fixtures.text("feeds/lantern-hill-arts.ics"), feed.url)
            val result = services.ingest.store(feed, events.filterNot { it.title.startsWith("Jazz") })
            assertEquals(1, result.removed)
            assertEquals(4, result.unchanged)
            assertTrue(titles(SearchQuery(text = "jazz")).isEmpty())
        }

    @Test
    fun aChangedListingUpdatesTheEvent() =
        runBlocking {
            val feed = services.feeds.list().first { it.url.endsWith("saltwater-running-club.ics") }
            val events = services.ingest.parse(feed, Fixtures.text("feeds/saltwater-running-club.ics"), feed.url)
            val result =
                services.ingest.store(
                    feed,
                    events.map {
                        if (it.title ==
                            "Sunset Trail Run"
                        ) {
                            it.copy(title = "Sunset Ridge Run")
                        } else {
                            it
                        }
                    },
                )
            assertEquals(1, result.updated)
            assertEquals(listOf("Sunset Ridge Run"), titles(SearchQuery(text = "ridge run")))
        }

    @Test
    fun deletingAFeedKeepsEventsThatAnotherFeedStillLists() =
        runBlocking {
            val hall = services.feeds.list().first { it.url.endsWith("harbourside-hall.html") }
            assertTrue(services.feeds.delete(hall.id))
            val concert = search(SearchQuery(text = "concert")).items.single()
            assertEquals(1, concert.sourceCount)
            assertTrue(titles(SearchQuery(text = "improv")).isEmpty())
        }

    @Test
    fun noContactOrAttendeeDataReachesTheDatabase() {
        TestDatabase.dataSource.connection.use { c ->
            val rs =
                c.createStatement().executeQuery(
                    """
                    SELECT (SELECT count(*) FROM event_source WHERE data::text ~* 'mailto|attendee|events@lantern-hill|hello@saltwater')
                         + (SELECT count(*) FROM event WHERE concat_ws(' ', title, description, organiser, venue) ~* 'mailto|attendee')
                    """.trimIndent(),
                )
            rs.next()
            assertEquals(0, rs.getInt(1))
        }
    }
}
