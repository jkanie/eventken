package io.github.jkanie.eventken.server.dev

import io.github.jkanie.eventken.server.ServerConfig
import io.github.jkanie.eventken.server.Services
import io.github.jkanie.eventken.server.db.Db
import io.github.jkanie.eventken.server.ingest.StoreResult
import io.github.jkanie.eventken.shared.model.FeedKind
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Loads the sample feeds in fixtures/ into the database, for local work. Each
 * file becomes a feed whose URL points at the fixture host (./gradlew
 * :server:fixtureHost), and its events are read straight from disk. Search
 * works at once, with no network. Running it again changes nothing.
 */
fun main(args: Array<String>) {
    val fixtures = File(args.firstOrNull() ?: "fixtures")
    require(fixtures.isDirectory) { "No fixtures folder at ${fixtures.absolutePath}." }
    val config = ServerConfig.fromEnvironment()
    val dataSource = Db.pool(config)
    Db.migrate(dataSource)
    val services = Services.create(config, dataSource)
    val baseUrl = System.getenv("EVENTKEN_FIXTURE_BASE_URL") ?: DEFAULT_FIXTURE_BASE_URL
    runBlocking {
        seed(services, fixtures, baseUrl).forEach { (path, parsed, result) ->
            println("$path: $parsed events, ${result.created} new, ${result.merged} merged, ${result.updated} updated")
        }
    }
    dataSource.close()
}

const val DEFAULT_FIXTURE_BASE_URL = "http://127.0.0.1:8089"

data class SeedLine(
    val path: String,
    val parsed: Int,
    val result: StoreResult,
)

/** Stores every sample feed through the same parse and merge steps a real fetch uses. */
suspend fun seed(
    services: Services,
    fixtures: File,
    baseUrl: String = DEFAULT_FIXTURE_BASE_URL,
): List<SeedLine> =
    seedFiles(fixtures).map { file ->
        val path = file.relativeTo(fixtures).invariantSeparatorsPath
        val kind = if (file.extension == "ics") FeedKind.ICAL else FeedKind.JSONLD
        val url = "$baseUrl/$path"
        val feed = services.feeds.findByUrl(url)?.feed ?: services.feeds.create(url, kind, defaultCategory = null)
        val events = services.ingest.parse(feed, file.readText(), url)
        SeedLine(path, events.size, services.ingest.store(feed, events))
    }

/** The sample feeds: every .ics file in feeds/ plus every .html page in pages/. */
fun seedFiles(fixtures: File): List<File> =
    (
        File(fixtures, "feeds").listFiles { f -> f.extension == "ics" }.orEmpty().toList() +
            File(fixtures, "pages").listFiles { f -> f.extension == "html" }.orEmpty().toList()
    ).sortedBy { it.path }
