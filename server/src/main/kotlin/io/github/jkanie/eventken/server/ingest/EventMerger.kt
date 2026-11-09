package io.github.jkanie.eventken.server.ingest

/**
 * Builds 1 event from the listings of every feed that carries it. The listing
 * seen first leads for title and times, because a later feed is more often a
 * copy. Other fields take the first listing that has them, the description
 * takes the longest text, categories are the union of all.
 */
object EventMerger {
    fun merge(sources: List<ParsedEvent>): ParsedEvent {
        require(sources.isNotEmpty()) { "An event needs at least 1 source." }
        val lead = sources.first()
        val located = sources.firstOrNull { it.latitude != null && it.longitude != null }
        return lead.copy(
            description = sources.mapNotNull { it.description }.maxByOrNull { it.length },
            venue = lead.venue ?: sources.firstNotNullOfOrNull { it.venue },
            address = lead.address ?: sources.firstNotNullOfOrNull { it.address },
            latitude = located?.latitude,
            longitude = located?.longitude,
            endsAt = lead.endsAt ?: sources.firstNotNullOfOrNull { it.endsAt },
            timeZone = lead.timeZone ?: sources.firstNotNullOfOrNull { it.timeZone },
            categories = sources.flatMap { it.categories }.distinct(),
            url = lead.url ?: sources.firstNotNullOfOrNull { it.url },
            organiser = lead.organiser ?: sources.firstNotNullOfOrNull { it.organiser },
            // 1 cancelled listing is enough: a feed that still lists the event may not have caught up yet.
            cancelled = sources.any { it.cancelled },
        )
    }
}
