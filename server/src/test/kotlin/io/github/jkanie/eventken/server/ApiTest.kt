package io.github.jkanie.eventken.server

import io.github.jkanie.eventken.server.dev.seed
import io.github.jkanie.eventken.server.ingest.IcsParser
import io.github.jkanie.eventken.server.support.FixedClock
import io.github.jkanie.eventken.server.support.FixtureServer
import io.github.jkanie.eventken.server.support.Fixtures
import io.github.jkanie.eventken.server.support.TestDatabase
import io.github.jkanie.eventken.shared.api.EventkenApiException
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.model.CategoryCount
import io.github.jkanie.eventken.shared.model.FeedKind
import io.github.jkanie.eventken.shared.model.Health
import io.github.jkanie.eventken.shared.model.NewFeed
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApiTest {
    // A made-up token for tests only.
    private val token = "test-admin-token-0123456789abcdef"
    private val clock = FixedClock(Instant.parse("2027-07-01T00:00:00Z"))

    @BeforeEach
    fun reset() = TestDatabase.reset()

    private fun api(
        config: ServerConfig = ServerConfig(adminToken = token),
        block: suspend ApplicationTestBuilder.(Services, EventkenClient) -> Unit,
    ) = testApplication {
        val services = Services.create(config, TestDatabase.dataSource, clock)
        application { eventken(services) }
        // The apps' own client talks to the server, so the JSON contract is tested from both ends.
        val eventken = EventkenClient("http://localhost", client.engine)
        block(services, eventken)
    }

    @Test
    fun searchThroughTheSharedClient() =
        api { services, eventken ->
            seed(services, Fixtures.dir)
            val page =
                eventken.search(
                    SearchQuery
                        .builder()
                        .text("concert")
                        .near(48.4236, -123.3702, 5.0)
                        .build(),
                )
            assertEquals(1, page.total)
            val concert = page.items.single()
            assertEquals(2, concert.sourceCount)
            assertEquals(0.02, concert.distanceKm)

            val detail = eventken.event(concert.id)
            assertEquals(2, detail.sources.size)
            assertTrue(eventken.categories().any { it.category == "music" })
        }

    @Test
    fun categoriesCanBeCutToTheMostUsed() =
        api { services, _ ->
            seed(services, Fixtures.dir)
            val all = Json.decodeFromString<List<CategoryCount>>(client.get("/api/categories").bodyAsText())
            assertTrue(all.size > 2, "the fixtures hold more than 2 categories: $all")
            val top = Json.decodeFromString<List<CategoryCount>>(client.get("/api/categories?limit=2").bodyAsText())
            assertEquals(all.take(2), top)
            for (bad in listOf("0", "101", "x", "")) {
                val response = client.get("/api/categories?limit=$bad")
                assertEquals(HttpStatusCode.BadRequest, response.status, "limit=$bad")
                assertEquals("""{"error":"bad_request","message":"limit must be between 1 and 100."}""", response.bodyAsText())
            }
        }

    @Test
    fun categoriesCanBeFoundByTheirStart() =
        api { services, _ ->
            seed(services, Fixtures.dir)

            suspend fun get(query: String) = Json.decodeFromString<List<CategoryCount>>(client.get("/api/categories$query").bodyAsText())
            val all = get("")
            val start = all.first().category.take(2)
            val want = all.filter { it.category.startsWith(start) }
            assertTrue(want.isNotEmpty())
            assertEquals(want, get("?q=$start"))
            assertEquals(want, get("?q=%20${start.uppercase()}%20"))
            assertEquals(want.take(1), get("?q=$start&limit=1"))
            assertEquals(all, get("?q="))
            assertEquals(emptyList(), get("?q=zzzz"))
        }

    @Test
    fun categoriesCanBeSortedByName() =
        api { services, eventken ->
            seed(services, Fixtures.dir)

            suspend fun get(query: String) = Json.decodeFromString<List<CategoryCount>>(client.get("/api/categories$query").bodyAsText())
            val all = get("")
            assertEquals(all.sortedBy { it.category }, get("?sort=name"))
            assertEquals(all, get("?sort=count"))
            // With a limit, the most used are kept, then put A to Z.
            assertEquals(all.take(3).sortedBy { it.category }, get("?limit=3&sort=name"))
            // The shared client sends each parameter.
            assertEquals(all.take(3).sortedBy { it.category }, eventken.categories(limit = 3, byName = true))
            val start = all.first().category.take(2)
            assertEquals(get("?q=$start"), eventken.categories(startingWith = start))
            assertEquals(all, eventken.categories())

            val bad = client.get("/api/categories?sort=newest")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("""{"error":"bad_request","message":"sort must be count or name."}""", bad.bodyAsText())
        }

    @Test
    fun badQueriesListEveryProblem() =
        api { _, _ ->
            val response = client.get("/api/events?lat=91&radiusKm=5&sort=sideways")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                """{"error":"invalid_query","message":"sort must be 1 of: relevance, start, distance. """ +
                    """lat and lon go together: give both or neither. lat must be between -90 and 90. radiusKm needs lat and lon."}""",
                response.bodyAsText(),
            )
        }

    @Test
    fun anEventDownloadsAsAnIcsFile() =
        api { services, eventken ->
            seed(services, Fixtures.dir)
            val concert =
                eventken
                    .search(SearchQuery.builder().text("concert").build())
                    .items
                    .single()
            val response = client.get("/api/events/${concert.id}/calendar.ics")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("text/calendar; charset=utf-8", response.headers[HttpHeaders.ContentType])
            assertEquals("attachment; filename=event-${concert.id}.ics", response.headers[HttpHeaders.ContentDisposition])
            val event = IcsParser(clock = clock).parse(response.bodyAsText()).single()
            assertEquals("event-${concert.id}@localhost", event.externalId)
            assertEquals(concert.title, event.title)
            assertEquals(concert.startsAt, event.startsAt)
            assertEquals(concert.endsAt, event.endsAt)
            // The shared client gives the same text.
            assertEquals(response.bodyAsText(), eventken.eventCalendar(concert.id))

            assertEquals(HttpStatusCode.NotFound, client.get("/api/events/999/calendar.ics").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/events/abc/calendar.ics").status)
        }

    @Test
    fun searchResultsDownloadAsOneIcsFile() =
        api { services, eventken ->
            seed(services, Fixtures.dir)
            val page = eventken.search(SearchQuery.builder().page(limit = 5, offset = 0).build())
            assertTrue(page.items.size >= 2, "the sample feeds need 2 or more events for this test")
            val response = client.get("/api/events/calendar.ics?limit=5")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("attachment; filename=events.ics", response.headers[HttpHeaders.ContentDisposition])
            val events = IcsParser(clock = clock).parse(response.bodyAsText())
            assertEquals(page.items.map { "event-${it.id}@localhost" }, events.map { it.externalId })
            assertEquals(page.items.map { it.title }, events.map { it.title })

            assertEquals(response.bodyAsText(), eventken.calendar(SearchQuery.builder().page(limit = 5, offset = 0).build()))
            val one = IcsParser(clock = clock).parse(client.get("/api/events/calendar.ics?limit=1").bodyAsText())
            assertEquals(listOf(page.items.first().title), one.map { it.title })
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/events/calendar.ics?sort=sideways").status)
        }

    @Test
    fun unknownEvents() =
        api { _, eventken ->
            assertEquals(HttpStatusCode.NotFound, client.get("/api/events/999").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/events/abc").status)
            val error = assertFailsWith<EventkenApiException> { eventken.event(999) }
            assertEquals("not_found", error.error?.error)
        }

    @Test
    fun statsCountEveryEventOnce() =
        api { services, eventken ->
            suspend fun stats(): Map<String, Long> {
                val response = client.get("/api/admin/stats") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject.mapValues { it.value.jsonPrimitive.long }
            }
            assertEquals(mapOf("events" to 0L, "upcoming" to 0L, "past" to 0L, "cancelled" to 0L, "feeds" to 0L), stats())

            seed(services, Fixtures.dir)
            val all = SearchQuery.builder().includeCancelled(true)
            for (now in listOf("2027-07-01T00:00:00Z", "2027-07-12T00:00:00Z")) {
                clock.now = Instant.parse(now)
                val counts = stats()
                assertEquals(listOf("events", "upcoming", "past", "cancelled", "feeds"), counts.keys.toList())
                assertEquals(counts["events"], counts["upcoming"]!! + counts["past"]!! + counts["cancelled"]!!, now)
                // upcoming is what search finds from now, and search with the cancelled ones adds those not ended yet.
                assertEquals(eventken.search(SearchQuery()).total, counts["upcoming"], now)
                assertTrue(counts["cancelled"]!! > 0, now)
                assertEquals(
                    services.feeds
                        .list()
                        .size
                        .toLong(),
                    counts["feeds"],
                    now,
                )
                assertTrue(eventken.search(all.build()).total <= counts["upcoming"]!! + counts["cancelled"]!!, now)
            }
            // Every fixture event starts in July 2027: none has ended on July 1, some have by July 12.
            clock.now = Instant.parse("2027-07-01T00:00:00Z")
            assertEquals(0L, stats()["past"])
            clock.now = Instant.parse("2027-07-12T00:00:00Z")
            assertTrue(stats()["past"]!! > 0)

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/stats").status)
        }

    @Test
    fun statsOf1FeedCountItsOwnEvents() =
        api { services, _ ->
            suspend fun stats(id: Long): Map<String, Long> {
                val response = client.get("/api/admin/feeds/$id/stats") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject.mapValues { it.value.jsonPrimitive.long }
            }
            val lines = seed(services, Fixtures.dir)
            val feeds = services.feeds.listStored()
            assertEquals(lines.size, feeds.size)
            clock.now = Instant.parse("2027-07-12T00:00:00Z")
            val all = client.get("/api/admin/stats") { bearerAuth(token) }.bodyAsText()
            val total =
                Json
                    .parseToJsonElement(all)
                    .jsonObject["events"]!!
                    .jsonPrimitive.long
            var sum = 0L
            for (line in lines) {
                val feed = feeds.single { it.feed.url.endsWith("/" + line.path) }
                val counts = stats(feed.feed.id)
                // Each event that the feed's file holds is counted once for that feed.
                assertEquals(line.parsed.toLong(), counts["events"], line.path)
                assertEquals(counts["events"], counts["upcoming"]!! + counts["past"]!! + counts["cancelled"]!!, line.path)
                assertEquals(1L, counts["feeds"], line.path)
                sum += counts["events"]!!
            }
            // An event that 2 feeds list counts for each, so the feeds add up to at least the total.
            assertTrue(sum >= total && total > 0, "$sum, $total")

            val empty = services.feeds.create("https://example.org/empty.ics", FeedKind.ICAL, defaultCategory = null)
            assertEquals(mapOf("events" to 0L, "upcoming" to 0L, "past" to 0L, "cancelled" to 0L, "feeds" to 1L), stats(empty.id))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/feeds/999999/stats") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/feeds/x/stats") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/feeds/${empty.id}/stats").status)
        }

    @Test
    fun adminEndpointsAreOffWithoutAToken() =
        api(ServerConfig(adminToken = null)) { _, _ ->
            val response = client.get("/api/admin/feeds")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("admin_disabled"))
        }

    @Test
    fun feedsCanBeListedByKind() =
        api { services, eventken ->
            val calendar = services.feeds.create("https://example.org/a.ics", FeedKind.ICAL, null)
            val page = services.feeds.create("https://example.org/events", FeedKind.JSONLD, null)

            suspend fun ids(query: String): List<Long> {
                val response = client.get("/api/admin/feeds$query") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
            }
            assertEquals(listOf(calendar.id), ids("?kind=ICAL"))
            assertEquals(listOf(page.id), ids("?kind=jsonld"))
            assertEquals(listOf(calendar.id, page.id), ids(""))
            // The shared client sends the kind by its name.
            assertEquals(listOf(page.id), eventken.feeds(token, kind = FeedKind.JSONLD).map { it.id })

            val bad = client.get("/api/admin/feeds?kind=PDF") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("""{"error":"bad_request","message":"kind must be ICAL or JSONLD."}""", bad.bodyAsText())
        }

    @Test
    fun feedsCanBeFoundByPartOfTheirUrl() =
        api { services, eventken ->
            val parks = services.feeds.create("https://Parks.example.org/events.ics", FeedKind.ICAL, null)
            val library = services.feeds.create("https://library.example.net/whats-on", FeedKind.JSONLD, "talks")
            val gigs = services.feeds.create("https://gigs.example.org/feed.ics", FeedKind.ICAL, "music")

            suspend fun ids(query: String): List<Long> {
                val response = client.get("/api/admin/feeds$query") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
            }
            assertEquals(listOf(parks.id, gigs.id), ids("?q=example.org"))
            assertEquals(listOf(parks.id), ids("?q=PARKS"))
            assertEquals(listOf(library.id), ids("?q=whats-on"))
            assertEquals(emptyList(), ids("?q=example.com"))
            // A blank q keeps every feed, and q works with the other filters.
            assertEquals(listOf(parks.id, library.id, gigs.id), ids("?q=%20"))
            assertEquals(listOf(gigs.id), ids("?q=example.org&kind=ical&category=music"))
            assertEquals(emptyList(), ids("?q=library&kind=ical"))
            // The shared client sends the same filters, "" as the category of the feeds with none.
            assertEquals(
                listOf(gigs.id),
                eventken.feeds(token, kind = FeedKind.ICAL, category = "music", urlContains = "EXAMPLE.ORG").map { it.id },
            )
            assertEquals(listOf(parks.id), eventken.feeds(token, category = "").map { it.id })
            assertEquals(listOf(library.id), eventken.feeds(token, urlContains = "whats-on", sort = "url").map { it.id })
        }

    @Test
    fun feedsCanBeSortedByUrlOrByEventCount() =
        api { services, eventken ->
            seed(services, Fixtures.dir)
            val zoo = services.feeds.create("https://Zoo.example.org/a.ics", FeedKind.ICAL, null)
            val art = services.feeds.create("https://art.example.org/b.ics", FeedKind.ICAL, null)

            suspend fun feeds(query: String): List<JsonObject> {
                val response = client.get("/api/admin/feeds$query") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status, query)
                return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
            }

            fun JsonObject.id() = this["id"]!!.jsonPrimitive.long

            val byId = feeds("")
            assertEquals(byId.map { it.id() }.sorted(), byId.map { it.id() })
            assertEquals(byId, feeds("?sort=id"))
            // The URL order ignores case, so Zoo comes last.
            val byUrl = feeds("?sort=url")
            assertEquals(
                byId.map { it["url"]!!.jsonPrimitive.content }.sortedBy { it.lowercase() },
                byUrl.map { it["url"]!!.jsonPrimitive.content },
            )
            assertEquals(zoo.id, byUrl.last().id())
            // The most events 1st, and the 2 empty feeds last, in id order.
            val byEvents = feeds("?sort=events")
            val counts = byEvents.map { it["eventCount"]!!.jsonPrimitive.long }
            assertEquals(counts.sortedDescending(), counts)
            assertTrue(counts.first() > 0)
            assertEquals(listOf(zoo.id, art.id), byEvents.takeLast(2).map { it.id() })
            // sort works with the filters, and the shared client sends it.
            assertEquals(listOf(art.id, zoo.id), feeds("?q=example.org&sort=url").map { it.id() })
            assertEquals(byEvents.map { it.id() }, eventken.feeds(token, sort = "events").map { it.id })
            assertEquals(byId.map { it.id() }, eventken.feeds(token).map { it.id })

            val bad = client.get("/api/admin/feeds?sort=newest") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("""{"error":"bad_request","message":"sort must be 1 of id, url, events."}""", bad.bodyAsText())
        }

    @Test
    fun feedsCanBeListedByDefaultCategory() =
        api { services, _ ->
            val music = services.feeds.create("https://example.org/a.ics", FeedKind.ICAL, "music")
            val talks = services.feeds.create("https://example.org/events", FeedKind.JSONLD, "talks")
            val plain = services.feeds.create("https://example.org/b.ics", FeedKind.ICAL, null)
            val musicPage = services.feeds.create("https://example.org/gigs", FeedKind.JSONLD, "music")

            suspend fun ids(query: String): List<Long> {
                val response = client.get("/api/admin/feeds$query") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
            }
            assertEquals(listOf(music.id, musicPage.id), ids("?category=music"))
            assertEquals(listOf(music.id, musicPage.id), ids("?category=%20Music%20"))
            assertEquals(listOf(talks.id), ids("?category=talks"))
            assertEquals(listOf(plain.id), ids("?category="))
            assertEquals(emptyList(), ids("?category=sport"))
            // With ?kind, both filters apply.
            assertEquals(listOf(musicPage.id), ids("?category=music&kind=jsonld"))
            assertEquals(listOf(plain.id), ids("?kind=ICAL&category="))
        }

    @Test
    fun aWrongTokenChangesNothing() =
        api { services, eventken ->
            val response =
                client.post("/api/admin/feeds") {
                    bearerAuth("wrong-token-wrong-token-wrong")
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"https://example.org/a.ics","kind":"ICAL"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("Bearer realm=\"eventken admin\"", response.headers[HttpHeaders.WWWAuthenticate])
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/feeds").status)
            assertTrue(services.feeds.list().isEmpty())
            assertFailsWith<EventkenApiException> { eventken.feeds("wrong-token-wrong-token-wrong") }
        }

    @Test
    fun privateFeedUrlsAreRefusedAtSaveTime() =
        api { _, eventken ->
            val error =
                assertFailsWith<EventkenApiException> {
                    eventken.addFeed(
                        token,
                        NewFeed("http://127.0.0.1:8089/feeds/a.ics", FeedKind.ICAL),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, error.status)
            assertEquals("invalid_feed", error.error?.error)
            assertEquals("Address 127.0.0.1 of 127.0.0.1 is a loopback address. Set ALLOW_PRIVATE_TARGETS to allow it.", error.message)

            val metadata =
                assertFailsWith<EventkenApiException> { eventken.addFeed(token, NewFeed("http://169.254.169.254/latest", FeedKind.JSONLD)) }
            assertTrue(metadata.message!!.contains("link-local"))
        }

    @Test
    fun malformedBodies() =
        api { _, _ ->
            val response =
                client.post("/api/admin/feeds") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"https://example.org/a.ics","kind":"PDF"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    @Test
    fun feedsAreAddedFetchedOverHttpAndRemoved() =
        FixtureServer().use { fixtures ->
            api(ServerConfig(adminToken = token, allowPrivateTargets = true)) { services, eventken ->
                val urls =
                    listOf(
                        "feeds/lantern-hill-arts.ics" to FeedKind.ICAL,
                        "feeds/saltwater-running-club.ics" to FeedKind.ICAL,
                        "pages/harbourside-hall.html" to FeedKind.JSONLD,
                        "pages/maple-commons-market.html" to FeedKind.JSONLD,
                        "private/members-only.html" to FeedKind.JSONLD,
                    )
                urls.forEach { (path, kind) ->
                    eventken.addFeed(token, NewFeed("${fixtures.baseUrl}/$path", kind, defaultCategory = "Victoria"))
                }

                val duplicate =
                    assertFailsWith<EventkenApiException> {
                        eventken.addFeed(
                            token,
                            NewFeed("${fixtures.baseUrl}/${urls[0].first}", FeedKind.ICAL),
                        )
                    }
                assertEquals(HttpStatusCode.Conflict, duplicate.status)

                val report = eventken.ingestNow(token)
                assertEquals(listOf(8, 3, 3, 4, 0), report.feeds.map { it.parsed })
                assertEquals(listOf(0, 0, 1, 1, 0), report.feeds.map { it.merged })
                assertEquals("error: robots.txt of 127.0.0.1 does not allow /private/members-only.html.", report.feeds.last().status)

                val page = eventken.search(SearchQuery(categories = listOf("victoria"), limit = 100))
                assertEquals(14, page.total)

                val feeds = eventken.feeds(token)
                assertEquals("ok: 8 events", feeds.first().lastStatus)
                assertEquals(8, feeds.first().eventCount)

                // 1 feed reads the same as its row in the list, and needs the token like the list.
                assertEquals(feeds.first(), eventken.feed(token, feeds.first().id))

                // The shared client reads the counts and fetches 1 feed again, as the routes answer.
                val stats = eventken.stats(token)
                assertEquals(stats.events, stats.upcoming + stats.past + stats.cancelled)
                assertEquals(feeds.size.toLong(), stats.feeds)
                assertEquals(8L, eventken.stats(token, feeds.first().id).events)
                val refetch = eventken.ingestFeed(token, feeds.first().id)
                assertEquals(feeds.first().id to 8, refetch.feedId to refetch.parsed)
                assertEquals(0, refetch.created)
                assertEquals(8L, eventken.stats(token, feeds.first().id).events)
                assertEquals(HttpStatusCode.NotFound, assertFailsWith<EventkenApiException> { eventken.ingestFeed(token, 999999) }.status)
                assertEquals(HttpStatusCode.NotFound, assertFailsWith<EventkenApiException> { eventken.stats(token, 999999) }.status)

                // The default category can be set (normalised as on create), cleared, and needs the token.
                val id = feeds.first().id
                assertEquals("night market", eventken.setFeedCategory(token, id, "  Night Market ").defaultCategory)
                assertEquals("night market", eventken.feed(token, id).defaultCategory)
                // Stored events take the category on the next fetch of the feed.
                assertEquals(0, eventken.categories().count { it.category == "night market" })
                eventken.ingestNow(token)
                assertTrue(eventken.categories().single { it.category == "night market" }.events > 0)
                assertEquals(null, eventken.setFeedCategory(token, id, null).defaultCategory)
                assertEquals(null, eventken.setFeedCategory(token, id, "   ").defaultCategory)
                val named = "{\"defaultCategory\":\"x\"}"
                val noToken =
                    client.put("/api/admin/feeds/$id/category") {
                        contentType(ContentType.Application.Json)
                        setBody(named)
                    }
                assertEquals(HttpStatusCode.Unauthorized, noToken.status)
                // The key is required, so an empty object cannot clear the category by mistake.
                val noKey =
                    client.put("/api/admin/feeds/$id/category") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.BadRequest, noKey.status)
                val notText =
                    client.put("/api/admin/feeds/$id/category") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("{\"defaultCategory\":5}")
                    }
                assertEquals(HttpStatusCode.BadRequest, notText.status)
                assertEquals(null, eventken.feed(token, id).defaultCategory)
                val missing =
                    client.put("/api/admin/feeds/999/category") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(named)
                    }
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/feeds/${feeds.first().id}").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/feeds/999") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/feeds/abc") { bearerAuth(token) }.status)

                // The fixture server sends no ETag, so a second run fetches again and finds nothing new.
                val again = eventken.ingestNow(token)
                assertTrue(again.feeds.all { it.created == 0 && it.merged == 0 })

                eventken.deleteFeed(token, feeds.first().id)
                assertEquals(4, eventken.feeds(token).size)
                assertEquals(HttpStatusCode.NotFound, client.post("/api/admin/feeds/999/ingest") { bearerAuth(token) }.status)
                assertTrue(services.feeds.find(feeds.first().id) == null)
            }
        }

    @Test
    fun healthAndOpenApi() =
        api { _, eventken ->
            val health = client.get("/health")
            assertEquals("""{"status":"ok","database":"ok"}""", health.bodyAsText())
            assertEquals("no-store", health.headers[HttpHeaders.CacheControl])
            // The shared client reads the same answer.
            assertEquals(Health("ok", "ok"), eventken.health())
            assertTrue(eventken.health().ok)
            val spec = client.get("/openapi.yaml")
            assertEquals(HttpStatusCode.OK, spec.status)
            assertTrue(spec.bodyAsText().startsWith("openapi: 3.0.3"))
            assertEquals(HttpStatusCode.OK, client.get("/docs").status)
        }
}
