package io.github.jkanie.eventken.shared

import io.github.jkanie.eventken.shared.cache.SavedEventStore
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.EventSource
import io.github.jkanie.eventken.shared.model.EventSummary
import io.github.jkanie.eventken.shared.platform.DesktopPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SavedEventStoreTest {
    private val fixedClock =
        object : Clock {
            override fun now() = Instant.parse("2026-06-20T12:00:00Z")
        }

    private fun detail(
        id: Long,
        start: String,
        title: String = "Event $id",
    ) = EventDetail(
        summary = EventSummary(id = id, title = title, startsAt = Instant.parse(start), categories = listOf("music")),
        description = "Line 1\nLine 2",
        organiser = "Fictional Arts Society",
        sources = listOf(EventSource(1, "https://example.org/a.ics", "uid-$id@example.org")),
    )

    private fun store() = SavedEventStore(DesktopPlatform.inMemoryDriver(), fixedClock, Dispatchers.Unconfined)

    @Test
    fun savedEventsComeBackWholeAndInStartOrder() {
        val store = store()
        store.save(detail(2, "2026-07-02T18:00:00Z"))
        store.save(detail(1, "2026-07-01T18:00:00Z"))

        val all = store.all()
        assertEquals(listOf(1L, 2L), all.map { it.detail.summary.id })
        assertEquals(detail(1, "2026-07-01T18:00:00Z"), all.first().detail)
        assertEquals(fixedClock.now(), all.first().savedAt)
    }

    @Test
    fun savingAgainUpdatesButKeepsTheReminderChoice() {
        val store = store()
        store.save(detail(1, "2026-07-01T18:00:00Z"))
        store.setReminders(1, true)
        store.save(detail(1, "2026-07-01T19:00:00Z", title = "Moved"))

        val saved = store.get(1)!!
        assertEquals("Moved", saved.detail.summary.title)
        assertTrue(saved.remindersOn)
        assertEquals(1, store.all().size)
    }

    @Test
    fun removeAndPrune() {
        val store = store()
        store.save(detail(1, "2026-05-01T18:00:00Z"))
        store.save(detail(2, "2026-07-01T18:00:00Z"))
        store.save(detail(3, "2026-08-01T18:00:00Z"))

        store.remove(3)
        assertFalse(store.isSaved(3))
        assertEquals(1, store.pruneEndedBefore(fixedClock.now()))
        assertNull(store.get(1))
        assertEquals(listOf(2L), store.all().map { it.detail.summary.id })
    }

    @Test
    fun observeEmitsTheCurrentList() =
        runBlocking {
            val store = store()
            store.save(detail(5, "2026-07-01T18:00:00Z"))
            assertEquals(listOf(5L), store.observeAll().first().map { it.detail.summary.id })
        }

    @Test
    fun aFileDatabaseKeepsEventsAcrossRestarts() {
        val file = Files.createTempDirectory("eventken").resolve("nested/saved.db").toFile()
        SavedEventStore(DesktopPlatform.savedEventsDriver(file), fixedClock).save(detail(8, "2026-07-01T18:00:00Z"))
        val reopened = SavedEventStore(DesktopPlatform.savedEventsDriver(file), fixedClock)
        assertEquals(
            "Event 8",
            reopened
                .get(8)
                ?.detail
                ?.summary
                ?.title,
        )
    }
}
