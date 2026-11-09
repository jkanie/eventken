package io.github.jkanie.eventken.android.calendar

import android.content.Intent
import android.provider.CalendarContract
import io.github.jkanie.eventken.shared.format.EventText
import io.github.jkanie.eventken.shared.model.EventDetail

/**
 * Builds the standard "insert event" intent. The user's calendar app opens with
 * the fields filled in, so eventken needs no calendar permission at all.
 */
object CalendarIntents {
    fun insert(detail: EventDetail): Intent {
        val event = detail.summary
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, event.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startsAt.toEpochMilliseconds())
            event.endsAt?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.toEpochMilliseconds()) }
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
            EventText.placeText(event)?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            val description = listOfNotNull(detail.description, event.url).joinToString("\n\n")
            if (description.isNotEmpty()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            event.timeZone?.let { putExtra(CalendarContract.Events.EVENT_TIMEZONE, it) }
        }
    }
}
