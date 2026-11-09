package io.github.jkanie.eventken.server.events

import io.github.jkanie.eventken.server.db.Db
import io.github.jkanie.eventken.server.db.SqlArray
import io.github.jkanie.eventken.server.db.doubleOrNull
import io.github.jkanie.eventken.server.db.instant
import io.github.jkanie.eventken.server.db.list
import io.github.jkanie.eventken.server.db.prepare
import io.github.jkanie.eventken.server.db.single
import io.github.jkanie.eventken.server.db.textArray
import io.github.jkanie.eventken.shared.geo.Geo
import io.github.jkanie.eventken.shared.model.CategoryCount
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.EventSource
import io.github.jkanie.eventken.shared.model.EventSummary
import io.github.jkanie.eventken.shared.model.SearchPage
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.github.jkanie.eventken.shared.search.SortOrder
import java.sql.ResultSet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.time.Instant

/** Read side: search, 1 event, category counts. */
class EventRepository(
    private val db: Db,
) {
    suspend fun search(
        query: SearchQuery,
        now: Instant,
    ): SearchPage {
        val sql = SearchSql.build(query, now)
        return db.read { c ->
            val total =
                c.prepare("SELECT count(*) FROM (${sql.inner}) x ${sql.outerWhere}", *sql.params.toTypedArray()).single { it.getLong(1) }
                    ?: 0
            val items =
                c
                    .prepare(
                        "SELECT * FROM (${sql.inner}) x ${sql.outerWhere} ORDER BY ${sql.orderBy} LIMIT ? OFFSET ?",
                        *(sql.params + query.limit + query.offset).toTypedArray(),
                    ).list(::toSummary)
            SearchPage(total = total, limit = query.limit, offset = query.offset, items = items)
        }
    }

    suspend fun find(id: Long): EventDetail? =
        db.read { c ->
            val row =
                c
                    .prepare(
                        "SELECT e.*, NULL::float8 AS distance_km, ${SearchSql.SOURCE_COUNT} AS source_count FROM event e WHERE e.id = ?",
                        id,
                    ).single { rs -> Triple(toSummary(rs), rs.getString("description"), rs.getString("organiser")) }
                    ?: return@read null
            val sources =
                c
                    .prepare(
                        """
                        SELECT s.feed_id, f.url AS feed_url, s.external_id, s.data ->> 'url' AS url
                        FROM event_source s JOIN feed f ON f.id = s.feed_id
                        WHERE s.event_id = ? ORDER BY s.first_seen_at, s.feed_id
                        """.trimIndent(),
                        id,
                    ).list { rs ->
                        EventSource(rs.getLong("feed_id"), rs.getString("feed_url"), rs.getString("external_id"), rs.getString("url"))
                    }
            EventDetail(summary = row.first, description = row.second, organiser = row.third, sources = sources)
        }

    /** Categories of events that have not ended yet, most used first. */
    suspend fun categories(now: Instant): List<CategoryCount> =
        db.read { c ->
            c
                .prepare(
                    """
                    SELECT category, count(*) AS events
                    FROM event e, unnest(e.categories) AS category
                    WHERE coalesce(e.ends_at, e.starts_at) >= ? AND NOT e.cancelled
                    GROUP BY category ORDER BY events DESC, category
                    """.trimIndent(),
                    now,
                ).list { CategoryCount(it.getString("category"), it.getLong("events")) }
        }

    private fun toSummary(rs: ResultSet) =
        EventSummary(
            id = rs.getLong("id"),
            title = rs.getString("title"),
            startsAt = rs.instant("starts_at")!!,
            endsAt = rs.instant("ends_at"),
            allDay = rs.getBoolean("all_day"),
            timeZone = rs.getString("time_zone"),
            venue = rs.getString("venue"),
            address = rs.getString("address"),
            latitude = rs.doubleOrNull("latitude"),
            longitude = rs.doubleOrNull("longitude"),
            categories = rs.textArray("categories"),
            url = rs.getString("url"),
            cancelled = rs.getBoolean("cancelled"),
            sourceCount = rs.getInt("source_count"),
            distanceKm = rs.doubleOrNull("distance_km")?.let { Math.round(it * 100) / 100.0 },
        )
}

/** Turns a [SearchQuery] into SQL text plus parameters. Every value from the query goes in as a parameter. */
internal object SearchSql {
    data class Built(
        val inner: String,
        val outerWhere: String,
        val orderBy: String,
        val params: List<Any?>,
    )

    const val SOURCE_COUNT = "(SELECT count(*) FROM event_source s WHERE s.event_id = e.id)"

    private const val KM_PER_DEGREE = Geo.EARTH_RADIUS_KM * kotlin.math.PI / 180

    // The haversine formula, as in Geo.haversineKm, so the server and the apps agree on distances.
    private val DISTANCE =
        """
        CASE WHEN o.lat IS NULL OR e.latitude IS NULL THEN NULL ELSE
            2 * ${Geo.EARTH_RADIUS_KM} * asin(least(1.0, sqrt(
                power(sin(radians(e.latitude - o.lat) / 2), 2) +
                cos(radians(o.lat)) * cos(radians(e.latitude)) * power(sin(radians(e.longitude - o.lon) / 2), 2)
            )))
        END
        """.trimIndent()

    fun build(
        query: SearchQuery,
        now: Instant,
    ): Built {
        val params = mutableListOf<Any?>(query.latitude, query.longitude, query.text)
        val where = mutableListOf<String>()

        // Without a start of the range, the search shows events that have not ended yet.
        where += "coalesce(e.ends_at, e.starts_at) >= ?"
        params += (query.from ?: now)
        query.to?.let {
            where += "e.starts_at <= ?"
            params += it
        }
        if (!query.includeCancelled) where += "NOT e.cancelled"
        if (query.categories.isNotEmpty()) {
            where += "e.categories && ?"
            params += SqlArray(query.categories)
        }
        if (!query.text.isNullOrBlank()) where += "e.search @@ t.tsq"

        val radius = query.radiusKm
        var outerWhere = ""
        val outerParams = mutableListOf<Any?>()
        val lat = query.latitude
        val lon = query.longitude
        if (radius != null && lat != null && lon != null) {
            // A box around the point first, so the index on (latitude, longitude) cuts the rows
            // before the exact distance is worked out.
            val latDelta = radius / KM_PER_DEGREE
            where += "e.latitude BETWEEN ? AND ?"
            params += lat - latDelta
            params += lat + latDelta
            val cosLat = cos(Math.toRadians(lat))
            if (abs(lat) + latDelta < 89.0 && cosLat > 0.01) {
                val lonDelta = radius / (KM_PER_DEGREE * cosLat)
                val west = lon - lonDelta
                val east = lon + lonDelta
                // A box that crosses the 180th meridian is skipped, and the exact distance alone decides.
                if (west >= -180.0 && east <= 180.0) {
                    where += "e.longitude BETWEEN ? AND ?"
                    params += west
                    params += east
                }
            }
            outerWhere = "WHERE x.distance_km <= ?"
            outerParams += radius
        }

        val inner =
            """
            WITH o AS (SELECT ?::float8 AS lat, ?::float8 AS lon),
                 t AS (SELECT websearch_to_tsquery('english', ?::text) AS tsq)
            SELECT e.*, $DISTANCE AS distance_km,
                   CASE WHEN t.tsq IS NULL THEN NULL ELSE ts_rank(e.search, t.tsq) END AS rank,
                   $SOURCE_COUNT AS source_count
            FROM event e, o, t
            WHERE ${where.joinToString(" AND ")}
            """.trimIndent()

        val orderBy =
            when (query.effectiveSort) {
                SortOrder.RELEVANCE -> "x.rank DESC, x.starts_at, x.id"
                SortOrder.START -> "x.starts_at, x.id"
                SortOrder.DISTANCE -> "x.distance_km NULLS LAST, x.starts_at, x.id"
            }
        return Built(inner, outerWhere, orderBy, params + outerParams)
    }
}
