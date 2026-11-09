package io.github.jkanie.eventken.android

import android.content.Intent
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jkanie.eventken.android.calendar.CalendarIntents
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.EventSummary
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class CalendarIntentsTest {
    private val detail =
        EventDetail(
            summary =
                EventSummary(
                    id = 1,
                    title = "Harbour Lights Summer Concert",
                    startsAt = Instant.parse("2027-07-11T02:30:00Z"),
                    endsAt = Instant.parse("2027-07-11T04:30:00Z"),
                    timeZone = "America/Vancouver",
                    venue = "Harbourside Hall",
                    address = "100 Wharf Lane",
                    url = "https://lantern-hill.example/events/harbour-lights",
                ),
            description = "Brass and strings.",
        )

    @Test
    fun fillsTheCalendarAppsInsertScreen() {
        val intent = CalendarIntents.insert(detail)
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Harbour Lights Summer Concert", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(
            Instant.parse("2027-07-11T02:30:00Z").toEpochMilliseconds(),
            intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0),
        )
        assertEquals(
            Instant.parse("2027-07-11T04:30:00Z").toEpochMilliseconds(),
            intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0),
        )
        assertFalse(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true))
        assertEquals("Harbourside Hall, 100 Wharf Lane", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals(
            "Brass and strings.\n\nhttps://lantern-hill.example/events/harbour-lights",
            intent.getStringExtra(CalendarContract.Events.DESCRIPTION),
        )
        assertEquals("America/Vancouver", intent.getStringExtra(CalendarContract.Events.EVENT_TIMEZONE))
    }
}
