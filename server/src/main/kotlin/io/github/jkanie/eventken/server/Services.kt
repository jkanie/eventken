package io.github.jkanie.eventken.server

import io.github.jkanie.eventken.server.db.Db
import io.github.jkanie.eventken.server.events.EventRepository
import io.github.jkanie.eventken.server.feeds.FeedRepository
import io.github.jkanie.eventken.server.ingest.IngestService
import io.github.jkanie.eventken.server.net.FeedFetcher
import io.github.jkanie.eventken.server.net.TargetGuard
import javax.sql.DataSource
import kotlin.time.Clock

/** Everything the HTTP layer needs, built once at start-up. Tests build their own. */
class Services(
    val config: ServerConfig,
    val db: Db,
    val guard: TargetGuard,
    val feeds: FeedRepository,
    val events: EventRepository,
    val ingest: IngestService,
    val clock: Clock = Clock.System,
) {
    companion object {
        fun create(
            config: ServerConfig,
            dataSource: DataSource,
            clock: Clock = Clock.System,
            guard: TargetGuard = TargetGuard(config.allowPrivateTargets),
            fetcher: FeedFetcher = FeedFetcher(guard, clock = clock),
        ): Services {
            val db = Db(dataSource)
            val feeds = FeedRepository(db)
            return Services(
                config = config,
                db = db,
                guard = guard,
                feeds = feeds,
                events = EventRepository(db),
                ingest = IngestService(db, feeds, fetcher, config.defaultZone, config.fetchConcurrency, clock),
                clock = clock,
            )
        }
    }
}
