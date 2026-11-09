package io.github.jkanie.eventken.server

import io.github.jkanie.eventken.server.dev.seed
import io.github.jkanie.eventken.server.support.FixedClock
import io.github.jkanie.eventken.server.support.FixtureServer
import io.github.jkanie.eventken.server.support.Fixtures
import io.github.jkanie.eventken.server.support.TestDatabase
import io.github.jkanie.eventken.shared.api.EventkenApiException
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.model.FeedKind
import io.github.jkanie.eventken.shared.model.NewFeed
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
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
    fun unknownEvents() =
        api { _, eventken ->
            assertEquals(HttpStatusCode.NotFound, client.get("/api/events/999").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/events/abc").status)
            val error = assertFailsWith<EventkenApiException> { eventken.event(999) }
            assertEquals("not_found", error.error?.error)
        }

    @Test
    fun adminEndpointsAreOffWithoutAToken() =
        api(ServerConfig(adminToken = null)) { _, _ ->
            val response = client.get("/api/admin/feeds")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("admin_disabled"))
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
                assertEquals(listOf(5, 3, 3, 4, 0), report.feeds.map { it.parsed })
                assertEquals(listOf(0, 0, 1, 1, 0), report.feeds.map { it.merged })
                assertEquals("error: robots.txt of 127.0.0.1 does not allow /private/members-only.html.", report.feeds.last().status)

                val page = eventken.search(SearchQuery(categories = listOf("victoria"), limit = 100))
                assertEquals(11, page.total)

                val feeds = eventken.feeds(token)
                assertEquals("ok: 5 events", feeds.first().lastStatus)
                assertEquals(5, feeds.first().eventCount)

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
        api { _, _ ->
            assertEquals("""{"status":"ok","database":"ok"}""", client.get("/health").bodyAsText())
            val spec = client.get("/openapi.yaml")
            assertEquals(HttpStatusCode.OK, spec.status)
            assertTrue(spec.bodyAsText().startsWith("openapi: 3.0.3"))
            assertEquals(HttpStatusCode.OK, client.get("/docs").status)
        }
}
