package io.github.jkanie.eventken.server.ingest

import io.github.jkanie.eventken.server.db.Db
import io.github.jkanie.eventken.server.feeds.FeedRepository
import io.github.jkanie.eventken.server.feeds.StoredFeed
import io.github.jkanie.eventken.server.net.FeedFetcher
import io.github.jkanie.eventken.server.net.FetchResult
import io.github.jkanie.eventken.shared.model.Feed
import io.github.jkanie.eventken.shared.model.FeedIngestResult
import io.github.jkanie.eventken.shared.model.FeedKind
import io.github.jkanie.eventken.shared.model.IngestReport
import io.github.jkanie.eventken.shared.search.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Fetches and parses feeds in parallel (up to [concurrency] at once, so 1 slow
 * publisher does not hold up the rest), then stores them 1 by 1 in feed order
 * through [EventStore]. The fixed order makes merges repeatable: the feed added
 * first always leads an event that 2 feeds share, however fast each 1 answers.
 */
class IngestService(
    private val db: Db,
    private val feeds: FeedRepository,
    private val fetcher: FeedFetcher,
    defaultZone: ZoneId,
    private val concurrency: Int = 4,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(IngestService::class.java)
    private val icsParser = IcsParser(defaultZone)
    private val jsonLdParser = JsonLdParser(defaultZone)

    private sealed interface Fetched {
        val startedAt: Instant

        data class Parsed(
            override val startedAt: Instant,
            val events: List<ParsedEvent>,
            val etag: String?,
            val lastModified: String?,
        ) : Fetched

        data class NotModified(
            override val startedAt: Instant,
        ) : Fetched

        data class Failed(
            override val startedAt: Instant,
            val status: String,
        ) : Fetched
    }

    suspend fun ingestAll(): IngestReport {
        val startedAt = clock.now()
        val semaphore = Semaphore(concurrency)
        val all = feeds.listStored()
        val fetched = coroutineScope { all.map { feed -> async { semaphore.withPermit { fetch(feed) } } }.awaitAll() }
        val results = all.zip(fetched).map { (feed, outcome) -> finish(feed.feed, outcome) }
        return IngestReport(startedAt, clock.now(), results)
    }

    suspend fun ingestFeed(id: Long): FeedIngestResult? = feeds.find(id)?.let { finish(it.feed, fetch(it)) }

    private suspend fun fetch(stored: StoredFeed): Fetched {
        val feed = stored.feed
        val startedAt = clock.now()
        return try {
            when (val result = fetcher.fetch(feed.url, stored.etag, stored.lastModified)) {
                FetchResult.NotModified -> {
                    Fetched.NotModified(startedAt)
                }

                is FetchResult.Fetched -> {
                    Fetched.Parsed(startedAt, parse(feed, result.body, result.finalUrl), result.etag, result.lastModified)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is IOException && e !is FeedParseException) log.warn("Fetch of feed {} failed", feed.id, e)
            Fetched.Failed(startedAt, "error: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun finish(
        feed: Feed,
        outcome: Fetched,
    ): FeedIngestResult =
        when (outcome) {
            is Fetched.NotModified -> {
                feeds.recordFetch(feed.id, outcome.startedAt, "not modified", null, null)
                FeedIngestResult(feed.id, feed.url, "not modified")
            }

            is Fetched.Failed -> {
                feeds.recordFetch(feed.id, outcome.startedAt, outcome.status, null, null)
                FeedIngestResult(feed.id, feed.url, outcome.status)
            }

            is Fetched.Parsed -> {
                val counts = store(feed, outcome.events)
                val status = "ok: ${outcome.events.size} events"
                feeds.recordFetch(feed.id, outcome.startedAt, status, outcome.etag, outcome.lastModified)
                FeedIngestResult(feed.id, feed.url, status, outcome.events.size, counts.created, counts.merged, counts.updated)
            }
        }

    fun parse(
        feed: Feed,
        body: String,
        url: String,
    ): List<ParsedEvent> {
        val events =
            when (feed.kind) {
                FeedKind.ICAL -> icsParser.parse(body)
                FeedKind.JSONLD -> jsonLdParser.parse(body, url)
            }
        if (events.size > MAX_EVENTS_PER_FEED) throw FeedParseException("Feed has ${events.size} events, more than $MAX_EVENTS_PER_FEED.")
        val category = feed.defaultCategory?.let(SearchQuery::normaliseCategory)?.ifEmpty { null }
        return if (category ==
            null
        ) {
            events
        } else {
            events.map { if (category in it.categories) it else it.copy(categories = it.categories + category) }
        }
    }

    suspend fun store(
        feed: Feed,
        events: List<ParsedEvent>,
    ): StoreResult = db.transaction { c -> EventStore.store(c, feed.id, events) }

    companion object {
        const val MAX_EVENTS_PER_FEED = 5_000
    }
}
