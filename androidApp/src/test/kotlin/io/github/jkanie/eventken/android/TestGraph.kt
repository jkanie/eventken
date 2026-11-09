package io.github.jkanie.eventken.android

import android.content.Context
import io.github.jkanie.eventken.android.location.GeoPoint
import io.github.jkanie.eventken.android.location.LocationProvider
import io.github.jkanie.eventken.android.reminders.ReminderScheduler
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.cache.SavedEventStore
import io.github.jkanie.eventken.shared.platform.AndroidPlatform
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.datetime.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant

/** A fixed position near the sample venues, in place of GPS. */
class FakeLocationProvider(
    private val point: GeoPoint? = GeoPoint(48.4236, -123.3702),
    private val permission: Boolean = true,
) : LocationProvider {
    override fun hasPermission() = permission

    override suspend fun current() = if (permission) point else null
}

class FixedClock(
    private val now: Instant,
) : Clock {
    override fun now() = now
}

const val CONCERT_JSON =
    """{"id":1,"title":"Harbour Lights Summer Concert","startsAt":"2027-07-11T02:30:00Z","endsAt":"2027-07-11T04:30:00Z",
"timeZone":"America/Vancouver","venue":"Harbourside Hall","address":"100 Wharf Lane","categories":["music"],
"url":"https://lantern-hill.example/events/harbour-lights","sourceCount":2,"distanceKm":0.02}"""

/** A fake eventken server with the sample concert, recording each request. */
class FakeServer {
    val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    val engine =
        MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/events" -> {
                    respond("""{"total":1,"limit":20,"offset":0,"items":[$CONCERT_JSON]}""", HttpStatusCode.OK, json)
                }

                "/api/events/1" -> {
                    respond(
                        """{"summary":$CONCERT_JSON,"description":"Brass and strings on the harbour steps.",
                           "organiser":"Lantern Hill Arts Collective","sources":[]}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }

                "/api/categories" -> {
                    respond("[]", HttpStatusCode.OK, json)
                }

                else -> {
                    respond("""{"error":"not_found","message":"Not here."}""", HttpStatusCode.NotFound, json)
                }
            }
        }
}

fun testGraph(
    context: Context,
    server: FakeServer = FakeServer(),
    location: LocationProvider = FakeLocationProvider(),
) = AppGraph(
    client = EventkenClient("http://events.test", server.engine),
    store = SavedEventStore(AndroidPlatform.savedEventsDriver(context, name = null)),
    location = location,
    reminders =
        ReminderScheduler(
            context,
            clock = FixedClock(Instant.parse("2027-07-01T00:00:00Z")),
            zone = { TimeZone.of("America/Vancouver") },
        ),
)
