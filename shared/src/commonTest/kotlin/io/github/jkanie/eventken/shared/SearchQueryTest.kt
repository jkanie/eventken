package io.github.jkanie.eventken.shared

import io.github.jkanie.eventken.shared.search.InvalidSearchQuery
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.github.jkanie.eventken.shared.search.SortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class SearchQueryTest {
    @Test
    fun builderProducesStableParameters() {
        val query =
            SearchQuery
                .builder()
                .text("  jazz night ")
                .near(48.4284, -123.3656, radiusKm = 5.0)
                .between(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-31T23:59:59Z"))
                .category("Music")
                .category("music")
                .category("Outdoor")
                .page(limit = 10, offset = 20)
                .build()

        assertEquals(
            listOf(
                "q" to "jazz night",
                "lat" to "48.4284",
                "lon" to "-123.3656",
                "radiusKm" to "5.0",
                "from" to "2026-07-01T00:00:00Z",
                "to" to "2026-07-31T23:59:59Z",
                "category" to "music",
                "category" to "outdoor",
                "limit" to "10",
                "offset" to "20",
            ),
            query.toParameters(),
        )
        assertEquals(SortOrder.RELEVANCE, query.effectiveSort)
    }

    @Test
    fun defaultsLeaveOutDefaultValues() {
        assertEquals(emptyList(), SearchQuery().toParameters())
        assertEquals(SortOrder.START, SearchQuery().effectiveSort)
    }

    @Test
    fun builderListsEveryProblem() {
        val error =
            assertFailsWith<InvalidSearchQuery> {
                SearchQuery
                    .builder()
                    .near(95.0, 200.0, radiusKm = 0.0)
                    .between(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"))
                    .sort(SortOrder.RELEVANCE)
                    .page(limit = 0)
                    .build()
            }
        assertEquals(
            listOf(
                "lat must be between -90 and 90.",
                "lon must be between -180 and 180.",
                "radiusKm must be above 0 and at most 500.0.",
                "from must not be after to.",
                "limit must be between 1 and 100.",
                "sort=relevance needs q.",
            ),
            error.problems,
        )
    }

    @Test
    fun radiusAndDistanceSortNeedALocation() {
        val problems = SearchQuery(radiusKm = 3.0, sort = SortOrder.DISTANCE).problems()
        assertTrue("radiusKm needs lat and lon." in problems)
        assertTrue("sort=distance needs lat and lon." in problems)
        assertTrue("lat and lon go together: give both or neither." in SearchQuery(latitude = 1.0).problems())
    }

    @Test
    fun parametersRoundTrip() {
        val original =
            SearchQuery(
                text = "market",
                latitude = 48.4,
                longitude = -123.3,
                radiusKm = 12.5,
                from = Instant.parse("2026-06-01T00:00:00Z"),
                categories = listOf("food", "outdoor"),
                sort = SortOrder.DISTANCE,
                limit = 50,
                includeCancelled = true,
            )
        val params = original.toParameters().groupBy({ it.first }, { it.second })
        assertEquals(original, SearchQuery.fromParameters { params[it].orEmpty() })
    }

    @Test
    fun fromParametersSplitsCommaCategoriesAndReportsBadNumbers() {
        val params = mapOf("category" to listOf("Music,food", " "), "lat" to listOf("north"), "limit" to listOf("ten"))
        val error = assertFailsWith<InvalidSearchQuery> { SearchQuery.fromParameters { params[it].orEmpty() } }
        assertEquals(listOf("lat must be a number.", "limit must be a whole number."), error.problems.take(2))

        val ok = SearchQuery.fromParameters { if (it == "category") listOf("Music,food", " ") else emptyList() }
        assertEquals(listOf("music", "food"), ok.categories)
    }

    @Test
    fun nextPageMovesTheOffset() {
        assertEquals(40, SearchQuery(limit = 20, offset = 20).nextPage().offset)
    }
}
