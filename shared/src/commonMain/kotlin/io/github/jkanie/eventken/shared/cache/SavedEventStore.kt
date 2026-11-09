package io.github.jkanie.eventken.shared.cache

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import io.github.jkanie.eventken.shared.model.EventDetail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

data class SavedEvent(
    val detail: EventDetail,
    val savedAt: Instant,
    val remindersOn: Boolean,
)

/**
 * Saved events on the device. The schema is created on first use, so a new
 * install needs no set-up step.
 */
class SavedEventStore(
    driver: SqlDriver,
    private val clock: Clock = Clock.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val queries: SavedEventQueries

    init {
        if (driver.userVersion() == 0L) {
            EventkenDatabase.Schema.create(driver)
            driver.setUserVersion(EventkenDatabase.Schema.version)
        }
        queries = EventkenDatabase(driver).savedEventQueries
    }

    fun save(detail: EventDetail) {
        val summary = detail.summary
        val startsAt = summary.startsAt.toEpochMilliseconds()
        val endsAt = summary.endsAt?.toEpochMilliseconds()
        val body = json.encodeToString(EventDetail.serializer(), detail)
        queries.transaction {
            queries.insertIfAbsent(summary.id, summary.title, startsAt, endsAt, body, clock.now().toEpochMilliseconds())
            queries.updateDetail(summary.title, startsAt, endsAt, body, summary.id)
        }
    }

    fun remove(id: Long) = queries.deleteById(id)

    fun setReminders(
        id: Long,
        on: Boolean,
    ) = queries.setReminders(if (on) 1 else 0, id)

    fun get(id: Long): SavedEvent? = queries.selectById(id).executeAsOneOrNull()?.toSaved()

    fun isSaved(id: Long): Boolean = get(id) != null

    fun all(): List<SavedEvent> = queries.selectAll().executeAsList().map { it.toSaved() }

    fun observeAll(): Flow<List<SavedEvent>> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toSaved() } }

    /** Drops events that ended before [cutoff], and returns how many went. */
    fun pruneEndedBefore(cutoff: Instant): Long = queries.deleteEndedBefore(cutoff.toEpochMilliseconds()).value

    private fun Saved_event.toSaved() =
        SavedEvent(
            detail = json.decodeFromString(EventDetail.serializer(), detail_json),
            savedAt = Instant.fromEpochMilliseconds(saved_at_ms),
            remindersOn = reminders_on != 0L,
        )
}

private fun SqlDriver.userVersion(): Long =
    executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult
                .Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

private fun SqlDriver.setUserVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version", 0)
}
