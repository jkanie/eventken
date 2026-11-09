package io.github.jkanie.eventken.shared.format

import io.github.jkanie.eventken.shared.model.EventSummary
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** Text for event cards, the same in both apps. */
object EventText {
    private val dateFormat =
        LocalDateTime.Format {
            dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
            char(' ')
            day()
            char(' ')
            monthName(MonthNames.ENGLISH_ABBREVIATED)
            char(' ')
            year()
        }

    private val timeFormat =
        LocalDateTime.Format {
            hour()
            char(':')
            minute()
        }

    /**
     * "Sat 10 Jul 2027, 19:30 to 21:30" in the event's own zone, because a
     * concert at 19:30 in Victoria starts at 19:30 wherever the reader is.
     */
    fun whenText(
        event: EventSummary,
        deviceZone: TimeZone,
    ): String {
        val zone = event.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: deviceZone
        val start = event.startsAt.toLocalDateTime(zone)
        if (event.allDay) {
            // An all-day end is midnight after the last day, so step back 1 second to name that day.
            val lastDay = event.endsAt?.minus(1.seconds)?.toLocalDateTime(zone)
            return if (lastDay == null || lastDay.date == start.date) {
                "${dateFormat.format(start)} (all day)"
            } else {
                "${dateFormat.format(start)} to ${dateFormat.format(lastDay)}"
            }
        }
        val end = event.endsAt?.toLocalDateTime(zone)
        return buildString {
            append(dateFormat.format(start)).append(", ").append(timeFormat.format(start))
            if (end != null) {
                append(" to ")
                if (end.date != start.date) append(dateFormat.format(end)).append(", ")
                append(timeFormat.format(end))
            }
        }
    }

    /** "250 m" under 1 km, "1.9 km" under 10 km, "12 km" beyond. */
    fun distanceText(km: Double): String =
        when {
            km < 1.0 -> "${((km * 1000) / 10).roundToInt() * 10} m"
            km < 10.0 -> "${(km * 10).roundToInt() / 10.0} km"
            else -> "${km.roundToInt()} km"
        }

    fun placeText(event: EventSummary): String? = listOfNotNull(event.venue, event.address).distinct().joinToString(", ").ifEmpty { null }
}
