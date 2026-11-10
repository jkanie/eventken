package io.github.jkanie.eventken.server.ingest

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The part of an RRULE (RFC 5545 section 3.3.10) that public event calendars use:
 * FREQ=DAILY, WEEKLY, MONTHLY or YEARLY with INTERVAL, COUNT, UNTIL, WKST, BYDAY
 * (with an ordinal such as 2TU or -1FR in MONTHLY and YEARLY rules), BYMONTHDAY,
 * BYMONTH. A rule with any other part (BYSETPOS, BYHOUR, BYWEEKNO and the like)
 * or another FREQ is not expanded: [parse] returns null, and the event keeps its
 * first date only.
 */
internal data class RecurrenceRule(
    val freq: Freq,
    val interval: Int = 1,
    val count: Int? = null,
    val until: Until? = null,
    val byDay: List<WeekdayNum> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** A weekday, with an optional ordinal: 1 is the 1st in the month (or year), -1 the last. */
    data class WeekdayNum(
        val ordinal: Int?,
        val day: DayOfWeek,
    )

    /** UNTIL as a UTC moment (a value ending in Z), a local date-time in the event's zone, or a date. */
    sealed interface Until {
        data class Moment(
            val epochSecond: Long,
        ) : Until

        data class Local(
            val dateTime: java.time.LocalDateTime,
        ) : Until

        data class Date(
            val date: LocalDate,
        ) : Until
    }

    /**
     * The start of every occurrence, in time order, from [start] (which always counts as the 1st, as RFC 5545
     * says) until COUNT, UNTIL or [horizon] stops the rule. Each later start keeps the wall-clock time of [start]
     * in its zone, so a weekly class at 18:00 stays at 18:00 across a daylight-saving change.
     */
    fun occurrences(
        start: ZonedDateTime,
        horizon: ZonedDateTime,
    ): Sequence<ZonedDateTime> =
        sequence {
            yield(start)
            var emitted = 1
            val time = start.toLocalTime()
            val zone = start.zone
            for (period in 0 until MAX_PERIODS) {
                val dates = candidates(start.toLocalDate(), period.toLong() * interval)
                if (dates == null) break
                for (date in dates) {
                    val at = ZonedDateTime.of(date, time, zone)
                    if (!at.isAfter(start)) continue
                    if (count != null && emitted >= count) return@sequence
                    if (pastUntil(at, date, time)) return@sequence
                    if (at.isAfter(horizon)) return@sequence
                    yield(at)
                    emitted++
                }
            }
        }

    private fun pastUntil(
        at: ZonedDateTime,
        date: LocalDate,
        time: LocalTime,
    ): Boolean =
        when (val u = until) {
            null -> false
            is Until.Moment -> at.toEpochSecond() > u.epochSecond
            is Until.Local -> date.atTime(time).isAfter(u.dateTime)
            is Until.Date -> date.isAfter(u.date)
        }

    /** The dates of 1 period (a day, week, month or year) [steps] periods after the 1st, in order, or null past the last valid year. */
    private fun candidates(
        first: LocalDate,
        steps: Long,
    ): List<LocalDate>? {
        val out =
            when (freq) {
                Freq.DAILY -> {
                    val day = first.plusDays(steps)
                    listOf(day).filter { matchesDaily(it) }
                }

                Freq.WEEKLY -> {
                    val weekOne = first.with(TemporalAdjusters.previousOrSame(weekStart))
                    val week = weekOne.plusWeeks(steps)
                    val days = byDay.map { it.day }.ifEmpty { listOf(first.dayOfWeek) }.toSet()
                    (0L..6L).map { week.plusDays(it) }.filter { it.dayOfWeek in days && monthOk(it) }
                }

                Freq.MONTHLY -> {
                    val month = YearMonth.from(first).plusMonths(steps)
                    if (byMonth.isNotEmpty() && month.monthValue !in byMonth) emptyList() else daysInMonth(month, first)
                }

                Freq.YEARLY -> {
                    val year = first.year + steps
                    if (year > MAX_YEAR) return null
                    val months = byMonth.ifEmpty { listOf(first.monthValue) }.sorted()
                    months.flatMap { daysInMonth(YearMonth.of(year.toInt(), it), first) }
                }
            }
        return out.sorted()
    }

    private fun monthOk(day: LocalDate) = byMonth.isEmpty() || day.monthValue in byMonth

    private fun matchesDaily(day: LocalDate): Boolean {
        if (!monthOk(day)) return false
        if (byMonthDay.isNotEmpty() && byMonthDay.none { monthDay(YearMonth.from(day), it) == day }) return false
        if (byDay.isNotEmpty() && byDay.none { it.day == day.dayOfWeek }) return false
        return true
    }

    /** The days of [month] that BYMONTHDAY and BYDAY pick, or the day of month of [first] when neither is set. */
    private fun daysInMonth(
        month: YearMonth,
        first: LocalDate,
    ): List<LocalDate> {
        val byMonthDays = byMonthDay.mapNotNull { monthDay(month, it) }.toSet()
        val byDays =
            byDay
                .flatMap { wd ->
                    val all = (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { it.dayOfWeek == wd.day }
                    when {
                        wd.ordinal == null -> all
                        wd.ordinal > 0 -> listOfNotNull(all.getOrNull(wd.ordinal - 1))
                        else -> listOfNotNull(all.getOrNull(all.size + wd.ordinal))
                    }
                }.toSet()
        return when {
            byMonthDay.isNotEmpty() && byDay.isNotEmpty() -> byMonthDays.intersect(byDays).toList()

            byMonthDay.isNotEmpty() -> byMonthDays.toList()

            byDay.isNotEmpty() -> byDays.toList()

            // A month without that day (the 31st in April, the 29th of February) has no occurrence.
            first.dayOfMonth <= month.lengthOfMonth() -> listOf(month.atDay(first.dayOfMonth))

            else -> emptyList()
        }
    }

    private fun monthDay(
        month: YearMonth,
        n: Int,
    ): LocalDate? {
        val length = month.lengthOfMonth()
        val day = if (n > 0) n else length + n + 1
        return if (day in 1..length) month.atDay(day) else null
    }

    companion object {
        /** Stops a rule whose periods never give a date in reach, such as BYMONTHDAY=31;BYMONTH=2. */
        private const val MAX_PERIODS = 20_000
        private const val MAX_YEAR = 9_999

        private val DAYS =
            mapOf(
                "MO" to DayOfWeek.MONDAY,
                "TU" to DayOfWeek.TUESDAY,
                "WE" to DayOfWeek.WEDNESDAY,
                "TH" to DayOfWeek.THURSDAY,
                "FR" to DayOfWeek.FRIDAY,
                "SA" to DayOfWeek.SATURDAY,
                "SU" to DayOfWeek.SUNDAY,
            )

        private val SUPPORTED = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST")

        /** Reads an RRULE value, or returns null when it uses a part this class does not expand or is malformed. */
        fun parse(value: String): RecurrenceRule? {
            val parts =
                value
                    .trim()
                    .split(';')
                    .filter { it.isNotBlank() }
                    .map { it.substringBefore('=').trim().uppercase() to it.substringAfter('=', "").trim().uppercase() }
            if (parts.any { it.first !in SUPPORTED }) return null
            val map = parts.toMap()
            if (map.size != parts.size) return null
            val freq = runCatching { Freq.valueOf(map["FREQ"] ?: return null) }.getOrNull() ?: return null
            val interval = map["INTERVAL"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..1000 } ?: return null } ?: 1
            val count = map["COUNT"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return null }
            if (count != null && map.containsKey("UNTIL")) return null
            val until = map["UNTIL"]?.let { parseUntil(it) ?: return null }
            val byDay =
                map["BYDAY"]?.split(',')?.map { item ->
                    val m = Regex("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)").matchEntire(item.trim()) ?: return null
                    val ordinal = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()
                    if (ordinal != null && (ordinal == 0 || ordinal !in -5..5)) return null
                    WeekdayNum(ordinal, DAYS.getValue(m.groupValues[2]))
                } ?: emptyList()
            // An ordinal BYDAY needs a month to count in: MONTHLY, or YEARLY with BYMONTH.
            if (byDay.any { it.ordinal != null } &&
                !(freq == Freq.MONTHLY || (freq == Freq.YEARLY && map.containsKey("BYMONTH")))
            ) {
                return null
            }
            val byMonthDay =
                map["BYMONTHDAY"]?.split(',')?.map { item ->
                    item.trim().toIntOrNull()?.takeIf { it != 0 && it in -31..31 } ?: return null
                } ?: emptyList()
            val byMonth =
                map["BYMONTH"]?.split(',')?.map { item ->
                    item.trim().toIntOrNull()?.takeIf { it in 1..12 } ?: return null
                } ?: emptyList()
            if (freq == Freq.WEEKLY && byMonthDay.isNotEmpty()) return null
            val weekStart = map["WKST"]?.let { DAYS[it] ?: return null } ?: DayOfWeek.MONDAY
            return RecurrenceRule(freq, interval, count, until, byDay, byMonthDay, byMonth, weekStart)
        }

        private fun parseUntil(text: String): Until? =
            runCatching {
                when {
                    Regex("\\d{8}").matches(text) -> {
                        Until.Date(LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
                    }

                    Regex("\\d{8}T\\d{6}Z").matches(text) -> {
                        val local = java.time.LocalDateTime.parse(text.dropLast(1), BASIC)
                        Until.Moment(local.toEpochSecond(java.time.ZoneOffset.UTC))
                    }

                    Regex("\\d{8}T\\d{6}").matches(text) -> {
                        Until.Local(java.time.LocalDateTime.parse(text, BASIC))
                    }

                    else -> {
                        null
                    }
                }
            }.getOrNull()

        private val BASIC =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss")
    }
}
