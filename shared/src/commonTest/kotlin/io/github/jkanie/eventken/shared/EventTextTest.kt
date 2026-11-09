package io.github.jkanie.eventken.shared

import io.github.jkanie.eventken.shared.format.EventText
import io.github.jkanie.eventken.shared.model.EventSummary
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class EventTextTest {
    private val concert =
        EventSummary(
            id = 1,
            title = "Harbour Lights Summer Concert",
            startsAt = Instant.parse("2027-07-11T02:30:00Z"),
            endsAt = Instant.parse("2027-07-11T04:30:00Z"),
            timeZone = "America/Vancouver",
            venue = "Harbourside Hall",
            address = "100 Wharf Lane",
        )

    @Test
    fun timesShowInTheEventZone() {
        assertEquals("Sat 10 Jul 2027, 19:30 to 21:30", EventText.whenText(concert, TimeZone.of("Europe/Berlin")))
    }

    @Test
    fun theDeviceZoneIsTheFallback() {
        assertEquals("Sun 11 Jul 2027, 02:30 to 04:30", EventText.whenText(concert.copy(timeZone = null), TimeZone.UTC))
    }

    @Test
    fun eventsPastMidnightNameTheEndDate() {
        val late = concert.copy(endsAt = Instant.parse("2027-07-11T08:00:00Z"))
        assertEquals("Sat 10 Jul 2027, 19:30 to Sun 11 Jul 2027, 01:00", EventText.whenText(late, TimeZone.UTC))
    }

    @Test
    fun allDayEvents() {
        val day =
            concert.copy(
                allDay = true,
                startsAt = Instant.parse("2027-07-17T07:00:00Z"),
                endsAt = Instant.parse("2027-07-18T07:00:00Z"),
            )
        assertEquals("Sat 17 Jul 2027 (all day)", EventText.whenText(day, TimeZone.UTC))
        val weekend = day.copy(endsAt = Instant.parse("2027-07-19T07:00:00Z"))
        assertEquals("Sat 17 Jul 2027 to Sun 18 Jul 2027", EventText.whenText(weekend, TimeZone.UTC))
    }

    @Test
    fun distances() {
        assertEquals("20 m", EventText.distanceText(0.0185))
        assertEquals("1.9 km", EventText.distanceText(1.86))
        assertEquals("12 km", EventText.distanceText(12.4))
    }

    @Test
    fun places() {
        assertEquals("Harbourside Hall, 100 Wharf Lane", EventText.placeText(concert))
        assertNull(EventText.placeText(concert.copy(venue = null, address = null)))
    }
}
