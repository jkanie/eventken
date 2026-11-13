package io.github.jkanie.eventken.server

import io.github.jkanie.eventken.server.api.AdminAuth
import io.github.jkanie.eventken.server.db.prepare
import io.github.jkanie.eventken.server.db.single
import io.github.jkanie.eventken.server.events.IcsWriter
import io.github.jkanie.eventken.server.feeds.DuplicateFeedException
import io.github.jkanie.eventken.server.ingest.IngestScheduler
import io.github.jkanie.eventken.server.net.TargetRefusedException
import io.github.jkanie.eventken.shared.model.ApiError
import io.github.jkanie.eventken.shared.model.FeedKind
import io.github.jkanie.eventken.shared.model.NewFeed
import io.github.jkanie.eventken.shared.search.InvalidSearchQuery
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val log = LoggerFactory.getLogger("io.github.jkanie.eventken.server")

/** The largest ?limit that GET /api/categories takes. */
const val MAX_CATEGORY_LIMIT = 100

fun Application.eventken(
    services: Services,
    scheduleIngest: Boolean = false,
) {
    install(ContentNegotiation) {
        json(
            Json {
                // Every field with a value is sent, defaults too, so clients in any language see the full shape.
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            },
        )
    }
    install(CallLogging) { level = Level.INFO }
    install(StatusPages) {
        exception<InvalidSearchQuery> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", e.message!!)) }
        exception<TargetRefusedException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError("invalid_feed", e.message!!)) }
        exception<DuplicateFeedException> { call, e -> call.respond(HttpStatusCode.Conflict, ApiError("duplicate_feed", e.message!!)) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "The request body is not valid JSON for this endpoint."))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "The request body is not valid JSON for this endpoint."))
        }
        exception<Throwable> { call, e ->
            log.error("Request failed", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Something went wrong on the server."))
        }
    }

    if (scheduleIngest) {
        services.config.ingestInterval?.let { interval ->
            IngestScheduler({ services.ingest.ingestAll() }, interval).start(this)
        }
    }

    routing {
        get("/health") {
            val database = runCatching { services.db.read { c -> c.prepare("SELECT 1").single { it.getInt(1) } } }.isSuccess
            // A cache between a monitor and the server must not answer for the server, least of all with an old 200.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                if (database) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf("status" to if (database) "ok" else "degraded", "database" to if (database) "ok" else "unreachable"),
            )
        }

        get("/openapi.yaml") {
            val text = Application::class.java.getResource("/openapi/documentation.yaml")!!.readText()
            call.respondText(text, ContentType.parse("application/yaml"))
        }
        swaggerUI(path = "docs", swaggerFile = "openapi/documentation.yaml")

        route("/api") {
            get("/events") {
                val query =
                    SearchQuery.fromParameters {
                        call.request.queryParameters
                            .getAll(it)
                            .orEmpty()
                    }
                call.respond(services.events.search(query, services.clock.now()))
            }
            get("/events/calendar.ics") {
                val query =
                    SearchQuery.fromParameters {
                        call.request.queryParameters
                            .getAll(it)
                            .orEmpty()
                    }
                val page = services.events.search(query, services.clock.now())
                val details = page.items.mapNotNull { services.events.find(it.id) }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "events.ics").toString(),
                )
                call.respondText(
                    IcsWriter.writeAll(details, call.request.host(), services.clock.now()),
                    ContentType.parse("text/calendar; charset=utf-8"),
                )
            }
            get("/events/{id}") {
                val id = call.longParameter("id") ?: return@get call.notFound("event")
                val event = services.events.find(id) ?: return@get call.notFound("event")
                call.respond(event)
            }
            get("/events/{id}/calendar.ics") {
                val id = call.longParameter("id") ?: return@get call.notFound("event")
                val event = services.events.find(id) ?: return@get call.notFound("event")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "event-$id.ics").toString(),
                )
                call.respondText(
                    IcsWriter.write(event, call.request.host(), services.clock.now()),
                    ContentType.parse("text/calendar; charset=utf-8"),
                )
            }
            get("/categories") {
                // ?limit=N keeps the N most used, such as for a row of filter chips.
                val limit = call.request.queryParameters["limit"]
                val top = limit?.toIntOrNull()?.takeIf { it in 1..MAX_CATEGORY_LIMIT }
                if (limit != null && top == null) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "limit must be between 1 and $MAX_CATEGORY_LIMIT."),
                    )
                }
                val categories = services.events.categories(services.clock.now())
                call.respond(if (top == null) categories else categories.take(top))
            }

            route("/admin") {
                install(AdminAuth) { token = services.config.adminToken }

                get("/feeds") {
                    val feeds = services.feeds.list()
                    val kind = call.request.queryParameters["kind"] ?: return@get call.respond(feeds)
                    val wanted =
                        FeedKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ApiError("bad_request", "kind must be ${FeedKind.entries.joinToString(" or ")}."),
                            )
                    call.respond(feeds.filter { it.kind == wanted })
                }

                post("/feeds") {
                    val body = call.receive<NewFeed>()
                    // The guard runs again on every fetch. This first check gives the admin an answer straight away.
                    val url = services.guard.checkUrl(body.url).toString()
                    val category = body.defaultCategory?.let(SearchQuery::normaliseCategory)?.ifEmpty { null }
                    call.respond(HttpStatusCode.Created, services.feeds.create(url, body.kind, category))
                }

                get("/feeds/{id}") {
                    val id = call.longParameter("id") ?: return@get call.notFound("feed")
                    val feed = services.feeds.find(id) ?: return@get call.notFound("feed")
                    call.respond(feed.feed)
                }

                put("/feeds/{id}/category") {
                    val id = call.longParameter("id") ?: return@put call.notFound("feed")
                    // The key is required, even when it is null, so an empty object never clears the category.
                    val value = call.receive<JsonObject>()["defaultCategory"]
                    if (value == null || (value != JsonNull && (value !is JsonPrimitive || !value.isString))) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("bad_request", "The body needs defaultCategory, a string or null."),
                        )
                    }
                    // Normalised as when the feed is added. Events already stored keep their categories until the
                    // next fetch of the feed.
                    val category = (value as? JsonPrimitive)?.contentOrNull?.let(SearchQuery::normaliseCategory)?.ifEmpty { null }
                    if (!services.feeds.setDefaultCategory(id, category)) return@put call.notFound("feed")
                    call.respond(services.feeds.find(id)!!.feed)
                }

                delete("/feeds/{id}") {
                    val id = call.longParameter("id") ?: return@delete call.notFound("feed")
                    if (services.feeds.delete(id)) call.respond(HttpStatusCode.NoContent) else call.notFound("feed")
                }

                post("/feeds/{id}/ingest") {
                    val id = call.longParameter("id") ?: return@post call.notFound("feed")
                    val result = services.ingest.ingestFeed(id) ?: return@post call.notFound("feed")
                    call.respond(result)
                }

                post("/ingest") { call.respond(services.ingest.ingestAll()) }
            }
        }
    }
}

private fun RoutingCall.longParameter(name: String): Long? = parameters[name]?.toLongOrNull()

private suspend fun RoutingCall.notFound(what: String) = respond(HttpStatusCode.NotFound, ApiError("not_found", "No $what with that id."))
