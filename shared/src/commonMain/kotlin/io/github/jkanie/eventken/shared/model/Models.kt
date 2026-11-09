package io.github.jkanie.eventken.shared.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** 1 event as search results show it. A merged event counts every source that listed it. */
@Serializable
data class EventSummary(
    val id: Long,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant? = null,
    val allDay: Boolean = false,
    val timeZone: String? = null,
    val venue: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val categories: List<String> = emptyList(),
    val url: String? = null,
    val cancelled: Boolean = false,
    val sourceCount: Int = 1,
    val distanceKm: Double? = null,
)

/** Where an event was seen: the feed and the identifier the feed gave it. */
@Serializable
data class EventSource(
    val feedId: Long,
    val feedUrl: String,
    val externalId: String,
    val url: String? = null,
)

@Serializable
data class EventDetail(
    val summary: EventSummary,
    val description: String? = null,
    val organiser: String? = null,
    val sources: List<EventSource> = emptyList(),
)

@Serializable
data class SearchPage(
    val total: Long,
    val limit: Int,
    val offset: Int,
    val items: List<EventSummary>,
)

@Serializable
data class CategoryCount(
    val category: String,
    val events: Long,
)

@Serializable
enum class FeedKind {
    /** An iCalendar (.ics) file, RFC 5545. */
    ICAL,

    /** A web page with schema.org Event objects in JSON-LD script tags. */
    JSONLD,
}

@Serializable
data class NewFeed(
    val url: String,
    val kind: FeedKind,
    val defaultCategory: String? = null,
)

@Serializable
data class Feed(
    val id: Long,
    val url: String,
    val kind: FeedKind,
    val defaultCategory: String? = null,
    val createdAt: Instant,
    val lastFetchedAt: Instant? = null,
    val lastStatus: String? = null,
    val eventCount: Long = 0,
)

@Serializable
data class FeedIngestResult(
    val feedId: Long,
    val url: String,
    val status: String,
    val parsed: Int = 0,
    val created: Int = 0,
    val merged: Int = 0,
    val updated: Int = 0,
)

@Serializable
data class IngestReport(
    val startedAt: Instant,
    val finishedAt: Instant,
    val feeds: List<FeedIngestResult>,
)

@Serializable
data class ApiError(
    val error: String,
    val message: String,
)
