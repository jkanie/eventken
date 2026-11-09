package io.github.jkanie.eventken.server.feeds

import io.github.jkanie.eventken.server.db.Db
import io.github.jkanie.eventken.server.db.instant
import io.github.jkanie.eventken.server.db.list
import io.github.jkanie.eventken.server.db.prepare
import io.github.jkanie.eventken.server.db.single
import io.github.jkanie.eventken.server.db.update
import io.github.jkanie.eventken.shared.model.Feed
import io.github.jkanie.eventken.shared.model.FeedKind
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.time.Instant

class DuplicateFeedException(
    url: String,
) : Exception("A feed with the URL $url exists already.")

/** A feed row plus the values kept for conditional requests, which the API does not show. */
data class StoredFeed(
    val feed: Feed,
    val etag: String?,
    val lastModified: String?,
)

class FeedRepository(
    private val db: Db,
) {
    suspend fun create(
        url: String,
        kind: FeedKind,
        defaultCategory: String?,
    ): Feed =
        try {
            db.transaction { c ->
                c
                    .prepare(
                        "INSERT INTO feed (url, kind, default_category) VALUES (?, ?, ?) RETURNING *, 0::bigint AS event_count",
                        url,
                        kind.name,
                        defaultCategory,
                    ).single(::toStored)!!
                    .feed
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") throw DuplicateFeedException(url) else throw e
        }

    suspend fun list(): List<Feed> = db.read { c -> c.prepare("$SELECT ORDER BY f.id").list(::toStored).map { it.feed } }

    suspend fun listStored(): List<StoredFeed> = db.read { c -> c.prepare("$SELECT ORDER BY f.id").list(::toStored) }

    suspend fun find(id: Long): StoredFeed? = db.read { c -> c.prepare("$SELECT WHERE f.id = ?", id).single(::toStored) }

    suspend fun findByUrl(url: String): StoredFeed? = db.read { c -> c.prepare("$SELECT WHERE f.url = ?", url).single(::toStored) }

    /** Deletes the feed with its listings, then every event that no other feed lists. */
    suspend fun delete(id: Long): Boolean =
        db.transaction { c ->
            val deleted = c.prepare("DELETE FROM feed WHERE id = ?", id).update() > 0
            if (deleted) {
                c.prepare("DELETE FROM event e WHERE NOT EXISTS (SELECT 1 FROM event_source s WHERE s.event_id = e.id)").update()
            }
            deleted
        }

    suspend fun recordFetch(
        id: Long,
        at: Instant,
        status: String,
        etag: String?,
        lastModified: String?,
    ) = db.transaction { c ->
        c
            .prepare(
                """
                UPDATE feed SET last_fetched_at = ?, last_status = ?,
                    etag = coalesce(?, etag), last_modified = coalesce(?, last_modified)
                WHERE id = ?
                """.trimIndent(),
                at,
                status.take(500),
                etag,
                lastModified,
                id,
            ).update()
    }

    private fun toStored(rs: ResultSet) =
        StoredFeed(
            feed =
                Feed(
                    id = rs.getLong("id"),
                    url = rs.getString("url"),
                    kind = FeedKind.valueOf(rs.getString("kind")),
                    defaultCategory = rs.getString("default_category"),
                    createdAt = rs.instant("created_at")!!,
                    lastFetchedAt = rs.instant("last_fetched_at"),
                    lastStatus = rs.getString("last_status"),
                    eventCount = rs.getLong("event_count"),
                ),
            etag = rs.getString("etag"),
            lastModified = rs.getString("last_modified"),
        )

    private companion object {
        const val SELECT =
            "SELECT f.*, (SELECT count(*) FROM event_source s WHERE s.feed_id = f.id) AS event_count FROM feed f "
    }
}
