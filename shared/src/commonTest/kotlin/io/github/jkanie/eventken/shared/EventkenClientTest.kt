package io.github.jkanie.eventken.shared

import io.github.jkanie.eventken.shared.api.EventkenApiException
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.model.FeedKind
import io.github.jkanie.eventken.shared.model.NewFeed
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class EventkenClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String,
    ) = EventkenClient(
        "http://events.test/",
        MockEngine { request ->
            requests += request
            respond(body, status, jsonHeaders)
        },
    )

    @Test
    fun searchSendsTheQueryAndReadsThePage() =
        runTest {
            val body =
                """
                {"total":1,"limit":20,"offset":0,"items":[{"id":3,"title":"Night Market","startsAt":"2026-07-04T01:00:00Z",
                "categories":["food"],"sourceCount":2,"distanceKm":1.25,"somethingNew":true}]}
                """.trimIndent()
            val page =
                client(body = body).search(
                    SearchQuery
                        .builder()
                        .text("market")
                        .near(48.42, -123.36, 5.0)
                        .build(),
                )

            assertEquals(1, page.total)
            assertEquals("Night Market", page.items.single().title)
            assertEquals(Instant.parse("2026-07-04T01:00:00Z"), page.items.single().startsAt)
            assertEquals(2, page.items.single().sourceCount)
            val url = requests.single().url
            assertEquals("/api/events", url.encodedPath)
            assertEquals("market", url.parameters["q"])
            assertEquals("5.0", url.parameters["radiusKm"])
        }

    @Test
    fun adminCallsCarryTheBearerToken() =
        runTest {
            val body = """{"id":4,"url":"https://example.org/events.ics","kind":"ICAL","createdAt":"2026-06-01T00:00:00Z"}"""
            val feed =
                client(
                    HttpStatusCode.Created,
                    body,
                ).addFeed("example-token", NewFeed("https://example.org/events.ics", FeedKind.ICAL))

            assertEquals(4, feed.id)
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer example-token", request.headers[HttpHeaders.Authorization])
            assertEquals(
                """{"url":"https://example.org/events.ics","kind":"ICAL"}""",
                (request.body as TextContent).text,
            )
        }

    @Test
    fun errorsCarryTheServerMessage() =
        runTest {
            val error =
                assertFailsWith<EventkenApiException> {
                    client(HttpStatusCode.BadRequest, """{"error":"invalid_feed","message":"Address 10.0.0.5 is private."}""")
                        .addFeed("t", NewFeed("http://10.0.0.5/x.ics", FeedKind.ICAL))
                }
            assertEquals(HttpStatusCode.BadRequest, error.status)
            assertEquals("invalid_feed", error.error?.error)
            assertEquals("Address 10.0.0.5 is private.", error.message)
        }

    @Test
    fun readsOneFeedWithTheToken() =
        runTest {
            val body =
                """{"id":4,"url":"https://example.org/events.ics","kind":"ICAL","createdAt":"2026-06-01T00:00:00Z",""" +
                    """"lastStatus":"ok: 8 events","eventCount":8}"""
            val feed = client(body = body).feed("example-token", 4)

            assertEquals(8, feed.eventCount)
            assertEquals("ok: 8 events", feed.lastStatus)
            val request = requests.single()
            assertEquals("/api/admin/feeds/4", request.url.encodedPath)
            assertEquals("Bearer example-token", request.headers[HttpHeaders.Authorization])

            val missing =
                assertFailsWith<EventkenApiException> {
                    client(HttpStatusCode.NotFound, """{"error":"not_found","message":"No such feed."}""").feed("t", 9)
                }
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }

    @Test
    fun calendarsComeBackAsTheServersText() =
        runTest {
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"
            val one = client(body = ics).eventCalendar(7)
            assertEquals(ics, one)
            assertEquals("/api/events/7/calendar.ics", requests.last().url.encodedPath)

            val all = client(body = ics).calendar(SearchQuery.builder().text("market").build())
            assertEquals(ics, all)
            val url = requests.last().url
            assertEquals("/api/events/calendar.ics", url.encodedPath)
            assertEquals("market", url.parameters["q"])

            assertFailsWith<IllegalArgumentException> { client(body = ics).calendar(SearchQuery(radiusKm = 3.0)) }
            assertEquals(2, requests.size)
            val error =
                assertFailsWith<EventkenApiException> {
                    client(HttpStatusCode.NotFound, """{"error":"not_found","message":"No such event."}""").eventCalendar(99)
                }
            assertEquals("No such event.", error.message)
        }

    @Test
    fun deleteAcceptsAnEmptyBody() =
        runTest {
            client(HttpStatusCode.NoContent, "").deleteFeed("t", 4)
            assertEquals("/api/admin/feeds/4", requests.single().url.encodedPath)
        }

    @Test
    fun invalidQueriesNeverLeaveTheDevice() =
        runTest {
            assertFailsWith<IllegalArgumentException> { client(body = "{}").search(SearchQuery(radiusKm = 3.0)) }
            assertEquals(0, requests.size)
        }
}
