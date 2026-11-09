package io.github.jkanie.eventken.server.ingest

import io.github.jkanie.eventken.shared.geo.Geo
import java.text.Normalizer
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/**
 * Decides whether 2 listings describe the same real-world event. Feeds rarely
 * share identifiers, so the test uses what they do share: a start time, a title,
 * a place. Each rule below exists because 1 alone gives false matches. Many
 * events start at 19:00, many titles say "Open Mic", many events use 1 hall.
 */
object EventMatcher {
    /** Listings of 1 event often differ by a few minutes, for doors versus show time. */
    val START_TOLERANCE = 30.minutes

    const val MAX_DISTANCE_KM = 1.0
    const val TITLE_THRESHOLD = 0.5

    /** With no place to compare, the titles alone must agree more closely. */
    const val TITLE_THRESHOLD_WITHOUT_PLACE = 0.8

    private val STOP_WORDS = setOf("the", "a", "an", "and", "of", "at", "in", "on", "with", "for", "to", "by")

    /** Returns a score from 0 to 1 when [a] and [b] match, or null when they do not. */
    fun score(
        a: ParsedEvent,
        b: ParsedEvent,
    ): Double? {
        if (a.allDay != b.allDay) return null
        val gap = abs((a.startsAt - b.startsAt).inWholeMinutes)
        if (gap > START_TOLERANCE.inWholeMinutes) return null

        val titleScore = similarity(tokens(a.title), tokens(b.title))
        val place = placeAgreement(a, b)
        val threshold = if (place == null) TITLE_THRESHOLD_WITHOUT_PLACE else TITLE_THRESHOLD
        if (place == false || titleScore < threshold) return null
        val timeScore = 1.0 - gap.toDouble() / (START_TOLERANCE.inWholeMinutes + 1)
        return 0.7 * titleScore + 0.3 * timeScore
    }

    /** True when the places agree, false when they disagree, null when there is nothing to compare. */
    private fun placeAgreement(
        a: ParsedEvent,
        b: ParsedEvent,
    ): Boolean? {
        if (a.latitude != null && a.longitude != null && b.latitude != null && b.longitude != null) {
            return Geo.haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) <= MAX_DISTANCE_KM
        }
        val venueA = tokens(a.venue.orEmpty())
        val venueB = tokens(b.venue.orEmpty())
        if (venueA.isEmpty() || venueB.isEmpty()) return null
        return similarity(venueA, venueB) >= TITLE_THRESHOLD
    }

    /** Lower case, accents removed, punctuation removed, small words dropped. */
    fun tokens(text: String): Set<String> =
        Normalizer
            .normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in STOP_WORDS }
            .toSet()

    /**
     * Overlap coefficient: shared words over the words of the shorter title. It
     * lets "Harbour Lights Concert" match "Harbour Lights Summer Concert", where
     * plain Jaccard similarity would punish the extra word.
     */
    fun similarity(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }
}
