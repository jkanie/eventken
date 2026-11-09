package io.github.jkanie.eventken.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.app.OrganiserModel
import io.github.jkanie.eventken.shared.app.SearchModel
import io.github.jkanie.eventken.shared.cache.SavedEventStore
import io.github.jkanie.eventken.shared.platform.DesktopPlatform
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the real screens off-screen against a fake server, so no display and no network are needed. */
@OptIn(ExperimentalTestApi::class)
class DesktopUiTest {
    private val requests = CopyOnWriteArrayList<Pair<HttpRequestData, String>>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val concert =
        """{"id":1,"title":"Harbour Lights Summer Concert","startsAt":"2027-07-11T02:30:00Z","endsAt":"2027-07-11T04:30:00Z",
            |"timeZone":"America/Vancouver","venue":"Harbourside Hall","categories":["music"],"sourceCount":2,"distanceKm":0.02}
        """.trimMargin()

    private val engine =
        MockEngine { request ->
            val body = runBlocking { request.body.toByteArray().decodeToString() }
            requests += request to body
            val path = request.url.encodedPath
            when {
                path == "/api/categories" -> {
                    respond("""[{"category":"music","events":1}]""", HttpStatusCode.OK, json)
                }

                path == "/api/events" -> {
                    respond("""{"total":1,"limit":20,"offset":0,"items":[$concert]}""", HttpStatusCode.OK, json)
                }

                path == "/api/events/1" -> {
                    respond(
                        """{"summary":$concert,"description":"Brass and strings on the harbour steps.","organiser":"Lantern Hill Arts Collective",
"sources":[{"feedId":1,"feedUrl":"https://lantern-hill.example/events.ics","externalId":"a"}]}
                        """.trimMargin(),
                        HttpStatusCode.OK,
                        json,
                    )
                }

                path == "/api/admin/feeds" && request.method == HttpMethod.Post -> {
                    if (body.contains("10.0.0.5")) {
                        respond(
                            """{"error":"invalid_feed","message":"Address 10.0.0.5 of 10.0.0.5 is a private address. Set ALLOW_PRIVATE_TARGETS to allow it."}""",
                            HttpStatusCode.BadRequest,
                            json,
                        )
                    } else {
                        respond(
                            """{"id":7,"url":"https://maple-commons.example/events","kind":"JSONLD","createdAt":"2027-01-01T00:00:00Z"}""",
                            HttpStatusCode.Created,
                            json,
                        )
                    }
                }

                path == "/api/admin/feeds" -> {
                    respond("[]", HttpStatusCode.OK, json)
                }

                else -> {
                    respond("""{"error":"not_found","message":"Not here."}""", HttpStatusCode.NotFound, json)
                }
            }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = EventkenClient("http://events.test", engine)
    private val store = SavedEventStore(DesktopPlatform.inMemoryDriver(), dispatcher = Dispatchers.Unconfined)
    private val search = SearchModel(client, store, scope)
    private val organiser = OrganiserModel(client, scope)

    @Test
    fun searchOpenAndSave() =
        runComposeUiTest {
            setContent { EventkenApp(search, organiser, "http://events.test") }

            onNodeWithTag(Tags.QUERY).performTextInput("concert")
            onNodeWithTag(Tags.SEARCH_BUTTON).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(Tags.result(1))).fetchSemanticsNodes().isNotEmpty() }

            onNodeWithText("Harbour Lights Summer Concert").assertIsDisplayed()
            onNodeWithText("Sat 10 Jul 2027, 19:30 to 21:30").assertIsDisplayed()
            onNodeWithText("2 sources").assertIsDisplayed()
            onNodeWithText("20 m").assertIsDisplayed()
            assertEquals(
                "concert",
                requests
                    .first { it.first.url.encodedPath == "/api/events" }
                    .first.url.parameters["q"],
            )

            onNodeWithTag(Tags.result(1)).performClick()
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodes(hasText("Organised by Lantern Hill Arts Collective")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(Tags.SAVE_BUTTON).performClick()
            waitUntil(timeoutMillis = 5_000) { store.isSaved(1) }

            onNodeWithTag(Tags.SAVED_TAB).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(Tags.saved(1))).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("1 saved").assertIsDisplayed()
        }

    @Test
    fun aRadiusWithoutALocationIsCaughtBeforeSending() =
        runComposeUiTest {
            setContent { EventkenApp(search, organiser, "http://events.test") }
            onNodeWithTag(Tags.RADIUS).performTextInput("5")
            onNodeWithTag(Tags.SEARCH_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(Tags.ERROR).assertIsDisplayed()
            onNodeWithText("Set a location to search by distance.").assertIsDisplayed()
            assertTrue(requests.none { it.first.url.encodedPath == "/api/events" })
        }

    @Test
    fun organiserAddsAFeedWithTheToken() =
        runComposeUiTest {
            setContent { EventkenApp(search, organiser, "http://events.test") }
            onNodeWithTag(Tags.ORGANISER_TAB).performClick()

            onNodeWithTag(Tags.TOKEN).performTextInput("example-token-for-tests-only")
            onNodeWithText("JSON-LD page").performClick()
            onNodeWithTag(Tags.FEED_URL).performTextInput("https://maple-commons.example/events")
            onNodeWithTag(Tags.ADD_FEED).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(Tags.feed(7))).fetchSemanticsNodes().isNotEmpty() }

            onNodeWithText("Added https://maple-commons.example/events.").assertIsDisplayed()
            val (request, body) = requests.last { it.first.method == HttpMethod.Post }
            assertEquals("Bearer example-token-for-tests-only", request.headers[HttpHeaders.Authorization])
            assertEquals("""{"url":"https://maple-commons.example/events","kind":"JSONLD"}""", body)
        }

    @Test
    fun organiserShowsTheServerReasonForARefusedFeed() =
        runComposeUiTest {
            setContent { EventkenApp(search, organiser, "http://events.test") }
            onNodeWithTag(Tags.ORGANISER_TAB).performClick()
            onNodeWithTag(Tags.TOKEN).performTextInput("example-token-for-tests-only")
            onNodeWithTag(Tags.FEED_URL).performTextInput("http://10.0.0.5/events.ics")
            onNodeWithTag(Tags.ADD_FEED).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(Tags.MESSAGE)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Address 10.0.0.5 of 10.0.0.5 is a private address. Set ALLOW_PRIVATE_TARGETS to allow it.").assertIsDisplayed()
        }
}
