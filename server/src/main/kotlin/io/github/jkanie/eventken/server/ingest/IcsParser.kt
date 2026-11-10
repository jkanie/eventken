package io.github.jkanie.eventken.server.ingest

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Reads VEVENT entries from an iCalendar file (RFC 5545). It covers what public
 * event calendars use: line folding, quoted parameters, escaped text, TZID,
 * all-day dates, DURATION, GEO, CATEGORIES, STATUS. VALARM blocks are skipped.
 * ATTENDEE lines and organiser e-mail addresses are dropped on purpose, so no
 * personal contact data reaches the database.
 *
 * A recurring event (RRULE, RDATE, EXDATE) becomes 1 event per occurrence that
 * has not ended yet and starts within the next 12 months, each with the
 * external id UID#start (start in UTC, or the date of an all-day event). A
 * changed instance (RECURRENCE-ID) replaces the occurrence it names. A rule
 * that [RecurrenceRule] cannot expand keeps its first date only.
 */
class IcsParser(
    private val defaultZone: ZoneId = ZoneOffset.UTC,
    private val clock: Clock = Clock.System,
) {
    data class Property(
        val name: String,
        val params: Map<String, String>,
        val value: String,
    )

    fun parse(text: String): List<ParsedEvent> {
        val lines = unfold(text)
        if (lines.none { it.equals("BEGIN:VCALENDAR", ignoreCase = true) }) {
            throw FeedParseException("Not an iCalendar file: BEGIN:VCALENDAR is missing.")
        }
        var calendarZone = defaultZone
        val components = mutableListOf<List<Property>>()
        var current: MutableList<Property>? = null
        var nestedDepth = 0

        for (line in lines) {
            val property = parseLine(line) ?: continue
            when {
                property.name == "BEGIN" && property.value.equals("VEVENT", ignoreCase = true) -> {
                    current = mutableListOf()
                    nestedDepth = 0
                }

                property.name == "BEGIN" && current != null -> {
                    nestedDepth++
                }

                property.name == "END" && property.value.equals("VEVENT", ignoreCase = true) && current != null -> {
                    components += current
                    current = null
                }

                property.name == "END" && current != null -> {
                    nestedDepth--
                }

                current != null && nestedDepth == 0 -> {
                    current += property
                }

                current == null && property.name == "X-WR-TIMEZONE" -> {
                    calendarZone = zoneOrNull(property.value) ?: calendarZone
                }
            }
        }
        return expand(components, calendarZone)
    }

    /** Turns each VEVENT into events: 1, or 1 per occurrence in the window for a recurring event. */
    private fun expand(
        components: List<List<Property>>,
        calendarZone: ZoneId,
    ): List<ParsedEvent> {
        val now = clock.now()
        val horizon = now.toJava().atZone(ZoneOffset.UTC).plusMonths(WINDOW_MONTHS)
        // The occurrences each UID's changed instances replace.
        val replaced =
            components
                .mapNotNull { props ->
                    val uid = props.uid() ?: return@mapNotNull null
                    val id = props.firstOrNull { it.name == "RECURRENCE-ID" } ?: return@mapNotNull null
                    parseDateValue(id, calendarZone)?.let { uid to occurrenceKey(it) }
                }.groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }

        val events = mutableListOf<ParsedEvent>()
        for (props in components) {
            val event = toEvent(props, calendarZone) ?: continue
            val uid = props.uid()
            val rrule = props.firstOrNull { it.name == "RRULE" }
            val rdates = props.filter { it.name == "RDATE" }
            if (uid == null || props.any { it.name == "RECURRENCE-ID" } || (rrule == null && rdates.isEmpty())) {
                events += event
                continue
            }
            val start = props.firstOrNull { it.name == "DTSTART" }?.let { parseDateValue(it, calendarZone) } ?: continue
            val rule = rrule?.let { RecurrenceRule.parse(it.value) }
            if (rrule != null && rule == null) {
                // A rule part that is not expanded: keep the first date, as a calendar without RRULE support would.
                events += event
                continue
            }
            val zone = start.zone ?: ZoneOffset.UTC
            val first = start.instant.toJava().atZone(zone)
            val starts =
                (rule?.occurrences(first, horizon) ?: sequenceOf(first))
                    .take(MAX_OCCURRENCES)
                    .toMutableList()
            for (rdate in rdates) {
                starts += dateValues(rdate, calendarZone).map { it.instant.toJava().atZone(zone) }
            }
            val excluded = props.filter { it.name == "EXDATE" }.flatMap { dateValues(it, calendarZone) }
            val excludedKeys = excluded.map(::occurrenceKey).toSet()
            val excludedDates =
                excluded
                    .filter { it.allDay }
                    .map {
                        it.instant
                            .toJava()
                            .atZone(it.zone ?: zone)
                            .toLocalDate()
                    }.toSet()
            val length = event.endsAt?.let { it - event.startsAt }
            val skip = replaced[uid].orEmpty()
            starts
                .distinct()
                .sorted()
                .asSequence()
                .map { DateValue(it.toInstant().toKotlin(), start.allDay, start.zone) to it }
                .filter { (value, at) ->
                    occurrenceKey(value) !in excludedKeys && at.toLocalDate() !in excludedDates && occurrenceKey(value) !in skip
                }.filter { (value, at) ->
                    val end = length?.let { value.instant + it } ?: value.instant
                    end >= now && !at.isAfter(horizon)
                }.take(MAX_OCCURRENCES)
                .forEach { (value, _) ->
                    events +=
                        event.copy(
                            externalId = "$uid#${occurrenceKey(value)}",
                            startsAt = value.instant,
                            endsAt = length?.let { value.instant + it },
                        )
                }
        }
        return events
    }

    private fun List<Property>.uid(): String? = firstOrNull { it.name == "UID" }?.value?.trim()?.ifEmpty { null }

    /** Every value of a comma list such as EXDATE:20270713T180000,20270720T180000, read with the property's parameters. */
    private fun dateValues(
        property: Property,
        calendarZone: ZoneId,
    ): List<DateValue> =
        property.value
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && '/' !in it } // RDATE periods (start/end) are not read
            .mapNotNull { parseDateValue(property.copy(value = it), calendarZone) }

    /** The start of an occurrence as its id part: the UTC date-time, or the date of an all-day event. */
    private fun occurrenceKey(value: DateValue): String {
        val at = value.instant.toJava()
        return if (value.allDay) {
            at.atZone(value.zone ?: ZoneOffset.UTC).toLocalDate().format(BASIC_DATE)
        } else {
            at.atZone(ZoneOffset.UTC).toLocalDateTime().format(BASIC_DATE_TIME) + "Z"
        }
    }

    private fun toEvent(
        props: List<Property>,
        calendarZone: ZoneId,
    ): ParsedEvent? {
        fun first(name: String) = props.firstOrNull { it.name == name }

        val start = first("DTSTART")?.let { parseDateValue(it, calendarZone) } ?: return null
        val title = first("SUMMARY")?.value?.let(::unescape).clean(500) ?: return null
        val end =
            first("DTEND")?.let { parseDateValue(it, calendarZone) }?.instant
                ?: first("DURATION")?.let { addDuration(start, it.value) }
                ?: if (start.allDay) start.plusDays(1) else null
        val geo =
            first("GEO")?.value?.split(';', ',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf {
                it.size == 2 && it[0] in -90.0..90.0 && it[1] in -180.0..180.0
            }
        val categories =
            props
                .filter { it.name == "CATEGORIES" }
                .flatMap { splitList(it.value) }
                .map { unescape(it).trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
        // A changed instance of a recurring event repeats the UID and adds RECURRENCE-ID, the start it replaces.
        val uid =
            first("UID")?.value?.trim()?.ifEmpty { null }?.let { id ->
                first("RECURRENCE-ID")?.let { rid ->
                    parseDateValue(rid, calendarZone)?.let { "$id#${occurrenceKey(it)}" } ?: "$id#${rid.value.trim()}"
                } ?: id
            }

        return ParsedEvent(
            externalId = uid ?: ParsedEvent.fallbackId(title, start.instant),
            title = title,
            startsAt = start.instant,
            endsAt = end?.takeIf { it >= start.instant },
            allDay = start.allDay,
            timeZone = start.zone?.id,
            description = first("DESCRIPTION")?.value?.let(::unescape).clean(),
            venue = first("LOCATION")?.value?.let(::unescape).clean(500),
            latitude = geo?.get(0),
            longitude = geo?.get(1),
            categories = categories,
            url = first("URL")?.value?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            // Only the display name. The value of ORGANIZER is usually a mailto: address.
            organiser = first("ORGANIZER")?.params?.get("CN")?.clean(200),
            cancelled = first("STATUS")?.value.equals("CANCELLED", ignoreCase = true),
        )
    }

    private data class DateValue(
        val instant: Instant,
        val allDay: Boolean,
        val zone: ZoneId?,
    ) {
        fun plusDays(days: Int): Instant = instant + (24 * days).hours
    }

    private fun parseDateValue(
        property: Property,
        calendarZone: ZoneId,
    ): DateValue? {
        val raw = property.value.trim()
        val isDate = property.params["VALUE"].equals("DATE", ignoreCase = true) || raw.matches(Regex("\\d{8}"))
        val zone = property.params["TZID"]?.let(::zoneOrNull) ?: calendarZone
        return runCatching {
            if (isDate) {
                val date = LocalDate.parse(raw.take(8), BASIC_DATE)
                DateValue(date.atStartOfDay(zone).toInstant().toKotlin(), allDay = true, zone = zone)
            } else if (raw.endsWith("Z")) {
                val local = LocalDateTime.parse(raw.dropLast(1), BASIC_DATE_TIME)
                DateValue(local.toInstant(ZoneOffset.UTC).toKotlin(), allDay = false, zone = property.params["TZID"]?.let(::zoneOrNull))
            } else {
                val local = LocalDateTime.parse(raw, BASIC_DATE_TIME)
                DateValue(local.atZone(zone).toInstant().toKotlin(), allDay = false, zone = zone)
            }
        }.getOrNull()
    }

    private fun addDuration(
        start: DateValue,
        value: String,
    ): Instant? {
        val text = value.trim()
        return runCatching {
            // java.time splits ISO 8601 durations into a date part (Period) and a time part (Duration).
            val negative = text.startsWith("-")
            val body = text.removePrefix("-").removePrefix("+")
            val tIndex = body.indexOf('T')
            val datePart = if (tIndex >= 0) body.substring(0, tIndex) else body
            val timePart = if (tIndex >= 0) "P" + body.substring(tIndex) else null
            var seconds = 0L
            if (datePart != "P") {
                val period = Period.parse(datePart)
                // RFC 5545 durations use weeks and days only, never months or years.
                seconds += period.days * 86_400L
            }
            if (timePart != null) seconds += Duration.parse(timePart).seconds
            if (negative) null else start.instant + seconds.seconds
        }.getOrNull()
    }

    companion object {
        /** How far ahead the occurrences of a recurring event go. */
        const val WINDOW_MONTHS = 12L

        /** At most this many occurrences of 1 recurring event, such as a daily event: 366 fit in the window. */
        const val MAX_OCCURRENCES = 400

        private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val BASIC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** Joins folded lines: a line that starts with a space or a tab continues the line before it. */
        fun unfold(text: String): List<String> {
            val out = mutableListOf<StringBuilder>()
            text.removePrefix("").split("\r\n", "\n", "\r").forEach { line ->
                if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                    out.last().append(line, 1, line.length)
                } else if (line.isNotEmpty()) {
                    out += StringBuilder(line)
                }
            }
            return out.map { it.toString() }
        }

        /** Splits a content line into its name, parameters, value. A quoted parameter value may hold colons, commas, parameter separators. */
        fun parseLine(line: String): Property? {
            var i = 0
            var inQuotes = false
            var colon = -1
            while (i < line.length) {
                val c = line[i]
                if (c == '"') inQuotes = !inQuotes
                if (c == ':' && !inQuotes) {
                    colon = i
                    break
                }
                i++
            }
            if (colon <= 0) return null
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val parts = splitOutsideQuotes(head, ';')
            val params =
                parts.drop(1).mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.substring(0, eq).uppercase() to part.substring(eq + 1).removeSurrounding("\"")
                }
            return Property(parts.first().uppercase(), params.toMap(), value)
        }

        fun unescape(value: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (val next = value[i + 1]) {
                        'n', 'N' -> out.append('\n')
                        else -> out.append(next)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /** Splits a comma list, keeping escaped commas (\,) inside their item. */
        private fun splitList(value: String): List<String> {
            val items = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    current.append(c).append(value[i + 1])
                    i += 2
                    continue
                }
                if (c == ',') {
                    items += current.toString()
                    current.clear()
                } else {
                    current.append(c)
                }
                i++
            }
            items += current.toString()
            return items
        }

        private fun splitOutsideQuotes(
            text: String,
            separator: Char,
        ): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            for (c in text) {
                if (c == '"') inQuotes = !inQuotes
                if (c == separator && !inQuotes) {
                    parts += current.toString()
                    current.clear()
                } else {
                    current.append(c)
                }
            }
            parts += current.toString()
            return parts
        }

        /** Accepts IANA names, also with the leading slash some calendar programs write. */
        fun zoneOrNull(name: String): ZoneId? = runCatching { ZoneId.of(name.trim().removePrefix("/")) }.getOrNull()
    }
}

internal fun java.time.Instant.toKotlin(): Instant = Instant.fromEpochSeconds(epochSecond, nano)

internal fun Instant.toJava(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())
