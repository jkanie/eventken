package io.github.jkanie.eventken.shared

import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.app.OrganiserModel
import io.github.jkanie.eventken.shared.app.SearchModel
import io.github.jkanie.eventken.shared.cache.SavedEventStore
import io.github.jkanie.eventken.shared.model.FeedKind
import io.github.jkanie.eventken.shared.platform.DesktopPlatform
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppModelsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val event =
        """{"id":1,"title":"Harbour Lights Summer Concert","startsAt":"2027-07-11T02:30:00Z","sourceCount":2}"""

    private val client =
        EventkenClient(
            "http://events.test",
            MockEngine { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/api/events" -> {
                        val offset = request.url.parameters["offset"]?.toInt() ?: 0
                        val items = if (offset == 0) "[$event]" else "[${event.replace("\"id\":1", "\"id\":2")}]"
                        respond("""{"total":2,"limit":1,"offset":$offset,"items":$items}""", HttpStatusCode.OK, json)
                    }

                    "/api/events/1" -> {
                        respond("""{"summary":$event,"description":"Brass and strings.","sources":[]}""", HttpStatusCode.OK, json)
                    }

                    "/api/admin/feeds" -> {
                        if (request.method.value == "POST") {
                            respond(
                                """{"id":3,"url":"https://example.org/a.ics","kind":"ICAL","createdAt":"2027-01-01T00:00:00Z"}""",
                                HttpStatusCode.Created,
                                json,
                            )
                        } else {
                            respond("[]", HttpStatusCode.OK, json)
                        }
                    }

                    else -> {
                        respond("""{"error":"not_found","message":"No event with that id."}""", HttpStatusCode.NotFound, json)
                    }
                }
            },
        )
    private val store = SavedEventStore(DesktopPlatform.inMemoryDriver(), dispatcher = Dispatchers.Unconfined)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun searchPagesAndSaves() =
        runBlocking {
            val model = SearchModel(client, store, scope, pageSize = 1)
            model.setText("concert")
            model.search()
            val first = withTimeout(5_000) { model.state.first { it.searched && !it.loading } }
            assertEquals(listOf(1L), first.results.map { it.id })
            assertTrue(first.canLoadMore)

            model.loadMore()
            val second = withTimeout(5_000) { model.state.first { it.results.size == 2 && !it.loading } }
            assertEquals(listOf(1L, 2L), second.results.map { it.id })

            model.open(1)
            withTimeout(5_000) { model.state.first { it.selected != null } }
            model.toggleSaved()
            assertTrue(store.isSaved(1))
            assertEquals("Brass and strings.", store.get(1)?.detail?.description)
        }

    @Test
    fun invalidInputNeverReachesTheServer() {
        val model = SearchModel(client, store, scope)
        model.setRadius(5.0)
        model.search()
        assertEquals("Set a location to search by distance.", model.state.value.error)
        model.setLocation(95.0, 0.0)
        model.search()
        assertEquals("lat must be between -90 and 90.", model.state.value.error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun savedEventsOpenWithoutTheNetwork() =
        runBlocking {
            val model = SearchModel(client, store, scope)
            model.open(1)
            withTimeout(5_000) { model.state.first { it.selected != null } }
            model.toggleSaved()
            model.close()

            val offline = SearchModel(EventkenClient("http://events.test", MockEngine { error("offline") }), store, scope)
            offline.open(1)
            assertEquals(
                "Harbour Lights Summer Concert",
                offline.state.value.selected
                    ?.summary
                    ?.title,
            )
        }

    @Test
    fun organiserNeedsATokenThenSendsIt() =
        runBlocking {
            val model = OrganiserModel(client, scope)
            model.add()
            assertEquals("Enter the admin token first.", model.state.value.message)

            model.setToken("  example-token-for-tests-only  ")
            model.setNewUrl("https://example.org/a.ics")
            model.setNewKind(FeedKind.ICAL)
            model.add()
            val done = withTimeout(5_000) { model.state.first { it.feeds.isNotEmpty() && !it.busy } }
            assertEquals("Added https://example.org/a.ics.", done.message)
            assertEquals("", done.newUrl)
            assertEquals("Bearer example-token-for-tests-only", requests.last().headers[HttpHeaders.Authorization])
        }
}
