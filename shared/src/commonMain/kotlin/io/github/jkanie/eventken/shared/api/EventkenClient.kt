package io.github.jkanie.eventken.shared.api

import io.github.jkanie.eventken.shared.model.ApiError
import io.github.jkanie.eventken.shared.model.CategoryCount
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.Feed
import io.github.jkanie.eventken.shared.model.IngestReport
import io.github.jkanie.eventken.shared.model.NewFeed
import io.github.jkanie.eventken.shared.model.SearchPage
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EventkenApiException(
    val status: HttpStatusCode,
    val error: ApiError?,
) : Exception(error?.message ?: "HTTP ${status.value} ${status.description}")

/**
 * The eventken JSON API for the apps. The engine is passed in, so the apps pick
 * OkHttp and the tests pick Ktor's mock engine.
 */
class EventkenClient(
    baseUrl: String,
    engine: HttpClientEngine,
) : AutoCloseable {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val http =
        HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            defaultRequest { url(baseUrl.trimEnd('/') + "/") }
        }

    suspend fun search(query: SearchQuery): SearchPage =
        http
            .get("api/events") {
                query.validated().toParameters().forEach { (name, value) -> parameter(name, value) }
            }.unwrap()

    suspend fun event(id: Long): EventDetail = http.get("api/events/$id").unwrap()

    suspend fun categories(): List<CategoryCount> = http.get("api/categories").unwrap()

    /** 1 event as an iCalendar file (RFC 5545), the text that the server sends. */
    suspend fun eventCalendar(id: Long): String = http.get("api/events/$id/calendar.ics").text()

    /** Every event that [query] finds, up to its limit, as 1 iCalendar file. */
    suspend fun calendar(query: SearchQuery): String =
        http
            .get("api/events/calendar.ics") {
                query.validated().toParameters().forEach { (name, value) -> parameter(name, value) }
            }.text()

    suspend fun feeds(adminToken: String): List<Feed> = http.get("api/admin/feeds") { admin(adminToken) }.unwrap()

    suspend fun feed(
        adminToken: String,
        id: Long,
    ): Feed = http.get("api/admin/feeds/$id") { admin(adminToken) }.unwrap()

    /** Sets the category that the feed's next fetches give to its events, or clears it with null. */
    suspend fun setFeedCategory(
        adminToken: String,
        id: Long,
        category: String?,
    ): Feed =
        http
            .put("api/admin/feeds/$id/category") {
                admin(adminToken)
                contentType(ContentType.Application.Json)
                // Built by hand: this client leaves nulls out, and the server needs the key even when it is null.
                setBody(buildJsonObject { put("defaultCategory", category) })
            }.unwrap()

    suspend fun addFeed(
        adminToken: String,
        feed: NewFeed,
    ): Feed =
        http
            .post("api/admin/feeds") {
                admin(adminToken)
                contentType(ContentType.Application.Json)
                setBody(feed)
            }.unwrap()

    suspend fun deleteFeed(
        adminToken: String,
        id: Long,
    ) {
        http.delete("api/admin/feeds/$id") { admin(adminToken) }.unwrap<Unit>()
    }

    suspend fun ingestNow(adminToken: String): IngestReport = http.post("api/admin/ingest") { admin(adminToken) }.unwrap()

    override fun close() = http.close()

    private fun HttpRequestBuilder.admin(token: String) = bearerAuth(token)

    private suspend fun HttpResponse.text(): String {
        failOnError()
        return bodyAsText()
    }

    private suspend fun HttpResponse.failOnError() {
        if (!status.isSuccess()) {
            val error = runCatching { json.decodeFromString<ApiError>(bodyAsText()) }.getOrNull()
            throw EventkenApiException(status, error)
        }
    }

    private suspend inline fun <reified T> HttpResponse.unwrap(): T {
        failOnError()
        if (T::class == Unit::class) return Unit as T
        return body()
    }
}
