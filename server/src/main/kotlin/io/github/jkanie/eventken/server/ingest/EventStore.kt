package io.github.jkanie.eventken.server.ingest

import io.github.jkanie.eventken.server.db.SqlArray
import io.github.jkanie.eventken.server.db.SqlJson
import io.github.jkanie.eventken.server.db.doubleOrNull
import io.github.jkanie.eventken.server.db.instant
import io.github.jkanie.eventken.server.db.list
import io.github.jkanie.eventken.server.db.prepare
import io.github.jkanie.eventken.server.db.single
import io.github.jkanie.eventken.server.db.textArray
import io.github.jkanie.eventken.server.db.update
import java.sql.Connection
import kotlin.time.Instant

/** Counts for 1 stored feed. */
data class StoreResult(
    val created: Int,
    val merged: Int,
    val updated: Int,
    val unchanged: Int,
    val removed: Int,
)

/**
 * Write side of ingest. Each feed is stored in 1 transaction that holds a
 * PostgreSQL advisory lock, so 2 feeds (or 2 server instances) that carry the
 * same event cannot both decide it is new and create it twice.
 */
object EventStore {
    private const val INGEST_LOCK = 0x6576_656e_746bL

    fun store(
        c: Connection,
        feedId: Long,
        events: List<ParsedEvent>,
    ): StoreResult {
        c.prepare("SELECT pg_advisory_xact_lock(?)", INGEST_LOCK).single { }
        // The database clock marks what this run saw: now() is the same for the whole
        // transaction and later for every later transaction, whatever the server clocks say.
        val seenAt = c.prepare("SELECT now()").single { it.instant("now") }!!
        var created = 0
        var merged = 0
        var updated = 0
        var unchanged = 0
        for (event in events) {
            val hash = event.contentHash()
            val existing =
                c
                    .prepare(
                        "SELECT event_id, content_hash FROM event_source WHERE feed_id = ? AND external_id = ?",
                        feedId,
                        event.externalId,
                    ).single { it.getLong(1) to it.getString(2) }
            when {
                existing != null && existing.second == hash -> {
                    c
                        .prepare(
                            "UPDATE event_source SET last_seen_at = ? WHERE feed_id = ? AND external_id = ?",
                            seenAt,
                            feedId,
                            event.externalId,
                        ).update()
                    unchanged++
                }

                existing != null -> {
                    c
                        .prepare(
                            "UPDATE event_source SET data = ?, content_hash = ?, last_seen_at = ? WHERE feed_id = ? AND external_id = ?",
                            SqlJson(event.toJson()),
                            hash,
                            seenAt,
                            feedId,
                            event.externalId,
                        ).update()
                    rebuild(c, existing.first)
                    updated++
                }

                else -> {
                    val match = bestMatch(c, feedId, event)
                    val eventId = match ?: insertEvent(c, event)
                    c
                        .prepare(
                            """
                            INSERT INTO event_source (feed_id, external_id, event_id, data, content_hash, first_seen_at, last_seen_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            feedId,
                            event.externalId,
                            eventId,
                            SqlJson(event.toJson()),
                            hash,
                            seenAt,
                            seenAt,
                        ).update()
                    if (match != null) {
                        rebuild(c, match)
                        merged++
                    } else {
                        created++
                    }
                }
            }
        }
        val removed = removeUnseen(c, feedId, seenAt)
        return StoreResult(created, merged, updated, unchanged, removed)
    }

    /**
     * Finds an event from another feed that describes the same thing. Events this
     * feed lists already are left out: 1 feed listing 2 alike events means 2 events.
     */
    private fun bestMatch(
        c: Connection,
        feedId: Long,
        event: ParsedEvent,
    ): Long? {
        val window = EventMatcher.START_TOLERANCE
        val candidates =
            c
                .prepare(
                    """
                    SELECT e.* FROM event e
                    WHERE e.starts_at BETWEEN ? AND ?
                      AND NOT EXISTS (SELECT 1 FROM event_source s WHERE s.event_id = e.id AND s.feed_id = ?)
                    """.trimIndent(),
                    event.startsAt - window,
                    event.startsAt + window,
                    feedId,
                ).list { rs ->
                    rs.getLong("id") to
                        ParsedEvent(
                            externalId = "event:${rs.getLong("id")}",
                            title = rs.getString("title"),
                            startsAt = rs.instant("starts_at")!!,
                            allDay = rs.getBoolean("all_day"),
                            venue = rs.getString("venue"),
                            latitude = rs.doubleOrNull("latitude"),
                            longitude = rs.doubleOrNull("longitude"),
                        )
                }
        return candidates
            .mapNotNull { (id, candidate) -> EventMatcher.score(event, candidate)?.let { id to it } }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun insertEvent(
        c: Connection,
        event: ParsedEvent,
    ): Long =
        c
            .prepare(
                """
                INSERT INTO event (title, description, starts_at, ends_at, all_day, time_zone, venue, address,
                                   latitude, longitude, categories, url, organiser, cancelled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """.trimIndent(),
                *values(event).toTypedArray(),
            ).single { it.getLong(1) }!!

    /** Rebuilds the event row from all its sources, the first seen first. */
    private fun rebuild(
        c: Connection,
        eventId: Long,
    ) {
        val sources =
            c
                .prepare("SELECT data::text FROM event_source WHERE event_id = ? ORDER BY first_seen_at, feed_id", eventId)
                .list { ParsedEvent.fromJson(it.getString(1)) }
        if (sources.isEmpty()) return
        val merged = EventMerger.merge(sources)
        c
            .prepare(
                """
                UPDATE event SET title = ?, description = ?, starts_at = ?, ends_at = ?, all_day = ?, time_zone = ?,
                    venue = ?, address = ?, latitude = ?, longitude = ?, categories = ?, url = ?, organiser = ?,
                    cancelled = ?, updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                *(values(merged) + eventId).toTypedArray(),
            ).update()
    }

    /** Drops listings this feed no longer has, then events that no feed lists any more. */
    private fun removeUnseen(
        c: Connection,
        feedId: Long,
        seenAt: Instant,
    ): Int {
        val affected =
            c
                .prepare("DELETE FROM event_source WHERE feed_id = ? AND last_seen_at < ? RETURNING event_id", feedId, seenAt)
                .list { it.getLong(1) }
                .distinct()
        affected.forEach { rebuild(c, it) }
        if (affected.isNotEmpty()) {
            c
                .prepare(
                    "DELETE FROM event e WHERE e.id = ANY(?::bigint[]) AND NOT EXISTS (SELECT 1 FROM event_source s WHERE s.event_id = e.id)",
                    SqlArray(affected.map { it.toString() }),
                ).update()
        }
        return affected.size
    }

    private fun values(event: ParsedEvent): List<Any?> =
        listOf(
            event.title,
            event.description,
            event.startsAt,
            event.endsAt,
            event.allDay,
            event.timeZone,
            event.venue,
            event.address,
            event.latitude,
            event.longitude,
            SqlArray(event.categories),
            event.url,
            event.organiser,
            event.cancelled,
        )

    /** For tests: the categories column as stored. */
    internal fun categories(
        c: Connection,
        eventId: Long,
    ): List<String> = c.prepare("SELECT categories FROM event WHERE id = ?", eventId).single { it.textArray("categories") }.orEmpty()
}
