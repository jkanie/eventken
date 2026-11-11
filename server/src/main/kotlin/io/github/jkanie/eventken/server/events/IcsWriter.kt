package io.github.jkanie.eventken.server.events

import io.github.jkanie.eventken.shared.model.EventDetail
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Writes events as an iCalendar file (RFC 5545), so a person can add them to their own
 * calendar. A timed event is written in UTC. An all-day event is written as dates in its
 * own time zone. X-WR-TIMEZONE names that zone when every event with a zone has the same
 * one. Lines end in CRLF and are folded at 75 octets without splitting a UTF-8 character.
 * The organiser's name is left out because ORGANIZER needs an address, and eventken keeps
 * none.
 */
object IcsWriter {
    private val date: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun write(
        detail: EventDetail,
        host: String,
        now: Instant,
    ): String = writeAll(listOf(detail), host, now)

    fun writeAll(
        details: List<EventDetail>,
        host: String,
        now: Instant,
    ): String {
        val zones = details.map { zoneOf(it) }
        val lines =
            mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//eventken//eventken//EN", "CALSCALE:GREGORIAN", "METHOD:PUBLISH")
        zones
            .filterNotNull()
            .distinct()
            .singleOrNull()
            ?.let { lines += "X-WR-TIMEZONE:${it.id}" }
        details.forEachIndexed { i, detail -> lines += vevent(detail, zones[i], host, now) }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    private fun zoneOf(detail: EventDetail): ZoneId? = detail.summary.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    private fun vevent(
        detail: EventDetail,
        zone: ZoneId?,
        host: String,
        now: Instant,
    ): List<String> {
        val event = detail.summary
        val lines = mutableListOf("BEGIN:VEVENT")
        lines += "UID:event-${event.id}@$host"
        lines += "DTSTAMP:${utc(now)}"
        if (event.allDay) {
            lines += "DTSTART;VALUE=DATE:${day(event.startsAt, zone)}"
            event.endsAt?.let { lines += "DTEND;VALUE=DATE:${day(it, zone)}" }
        } else {
            lines += "DTSTART:${utc(event.startsAt)}"
            event.endsAt?.let { lines += "DTEND:${utc(it)}" }
        }
        lines += "SUMMARY:${escape(event.title)}"
        detail.description?.let { lines += "DESCRIPTION:${escape(it)}" }
        listOfNotNull(event.venue, event.address).joinToString(", ").ifEmpty { null }?.let { lines += "LOCATION:${escape(it)}" }
        if (event.latitude != null && event.longitude != null) lines += "GEO:${event.latitude};${event.longitude}"
        if (event.categories.isNotEmpty()) lines += "CATEGORIES:${event.categories.joinToString(",") { escape(it) }}"
        event.url?.let { lines += "URL:$it" }
        lines += "STATUS:${if (event.cancelled) "CANCELLED" else "CONFIRMED"}"
        lines += "END:VEVENT"
        return lines
    }

    private fun utc(at: Instant): String = dateTime.format(at.toJavaInstant().atOffset(ZoneOffset.UTC))

    private fun day(
        at: Instant,
        zone: ZoneId?,
    ): String = date.format(at.toJavaInstant().atZone(zone ?: ZoneOffset.UTC).toLocalDate())

    /** RFC 5545 section 3.3.11 -- back-slash, semi-colon, comma, line-break. */
    fun escape(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")

    /** RFC 5545 section 3.1: at most 75 octets per line, a continued line starts with a space. */
    fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (octets + size > limit) {
                out.append("\r\n ")
                octets = 0
                limit = 74
            }
            out.appendCodePoint(cp)
            octets += size
            i += chars
        }
        return out.toString()
    }
}
