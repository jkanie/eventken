package io.github.jkanie.eventken.server.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeedFetcherTest {
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String?>>>()
    private val routes = mutableMapOf<String, (HttpExchange) -> Unit>()

    // Listens on all addresses, so 127.0.0.1 and 127.0.0.2 both reach it.
    private val server =
        HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.toString()
                requests += path to
                    mapOf(
                        "User-Agent" to exchange.requestHeaders.getFirst("User-Agent"),
                        "If-None-Match" to exchange.requestHeaders.getFirst("If-None-Match"),
                        "Host" to exchange.requestHeaders.getFirst("Host"),
                    )
                val handler = routes[exchange.requestURI.path] ?: { it.send(404, "") }
                handler(exchange)
            }
            start()
        }
    private val port = server.address.port

    @AfterEach
    fun stop() = server.stop(0)

    private fun HttpExchange.send(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    private val localGuard = TargetGuard(allowPrivateTargets = true)

    @Test
    fun fetchesWithAUserAgentAndReadsCachingHeaders() =
        runBlocking {
            routes["/feed.ics"] =
                { it.send(200, "BEGIN:VCALENDAR", mapOf("ETag" to "\"v1\"", "Last-Modified" to "Sat, 01 May 2027 10:00:00 GMT")) }
            val result = FeedFetcher(localGuard).fetch("http://127.0.0.1:$port/feed.ics")

            assertIs<FetchResult.Fetched>(result)
            assertEquals("BEGIN:VCALENDAR", result.body)
            assertEquals("\"v1\"", result.etag)
            val feedRequest = requests.single { it.first == "/feed.ics" }
            assertEquals("eventken/0.1 (+https://github.com/jkanie/eventken)", feedRequest.second["User-Agent"])
            assertTrue(requests.any { it.first == "/robots.txt" }, "robots.txt is read first")
        }

    @Test
    fun conditionalRequestsGetNotModified() =
        runBlocking {
            routes["/feed.ics"] = { if (it.requestHeaders.getFirst("If-None-Match") == "\"v1\"") it.send(304, "") else it.send(200, "x") }
            assertEquals(FetchResult.NotModified, FeedFetcher(localGuard).fetch("http://127.0.0.1:$port/feed.ics", etag = "\"v1\""))
        }

    @Test
    fun robotsTxtIsObeyed() =
        runBlocking {
            routes["/robots.txt"] = { it.send(200, "User-agent: *\nDisallow: /private/\n") }
            routes["/private/feed.ics"] = { it.send(200, "secret") }
            val error = assertFailsWith<FetchException> { FeedFetcher(localGuard).fetch("http://127.0.0.1:$port/private/feed.ics") }
            assertEquals("robots.txt of 127.0.0.1 does not allow /private/feed.ics.", error.message)
            assertTrue(requests.none { it.first == "/private/feed.ics" })
        }

    @Test
    fun robotsTxtServerErrorsMeanStayAway() =
        runBlocking {
            routes["/robots.txt"] = { it.send(503, "busy") }
            routes["/feed.ics"] = { it.send(200, "x") }
            assertFailsWith<FetchException> { FeedFetcher(localGuard).fetch("http://127.0.0.1:$port/feed.ics") }
            Unit
        }

    @Test
    fun followsRedirectsUpToTheLimit() =
        runBlocking {
            routes["/old.ics"] = { it.send(301, "", mapOf("Location" to "/new.ics")) }
            routes["/new.ics"] = { it.send(200, "moved") }
            val result = FeedFetcher(localGuard).fetch("http://127.0.0.1:$port/old.ics")
            assertEquals("moved", (result as FetchResult.Fetched).body)
            assertEquals("http://127.0.0.1:$port/new.ics", result.finalUrl)

            routes["/loop.ics"] = { it.send(302, "", mapOf("Location" to "/loop.ics")) }
            val error = assertFailsWith<FetchException> { FeedFetcher(localGuard).fetch("http://127.0.0.1:$port/loop.ics") }
            assertTrue(error.message!!.startsWith("More than 5 redirects"))
        }

    @Test
    fun aRedirectToABlockedAddressIsRefused() =
        runBlocking {
            // 127.0.0.1 stands in for a public server, 127.0.0.2 for an internal 1.
            val guard =
                TargetGuard(
                    allowPrivateTargets = false,
                    resolver = { host ->
                        when (host) {
                            "public.test" -> listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
                            "internal.test" -> listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 2)))
                            else -> InetAddress.getAllByName(host).toList()
                        }
                    },
                    addressPolicy = { if (it.address[3] == 2.toByte()) "an internal address" else null },
                )
            routes["/feed.ics"] = { it.send(302, "", mapOf("Location" to "http://internal.test:$port/admin")) }
            routes["/admin"] = { it.send(200, "internal data") }

            val error = assertFailsWith<TargetRefusedException> { FeedFetcher(guard).fetch("http://public.test:$port/feed.ics") }
            assertEquals("Address 127.0.0.2 of internal.test is an internal address. Set ALLOW_PRIVATE_TARGETS to allow it.", error.message)
            assertTrue(requests.none { it.first == "/admin" })
        }

    @Test
    fun loopbackIsRefusedByDefault() =
        runBlocking {
            routes["/feed.ics"] = { it.send(200, "x") }
            assertFailsWith<TargetRefusedException> { FeedFetcher(TargetGuard(false)).fetch("http://127.0.0.1:$port/feed.ics") }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun oversizedFeedsAreRefused() =
        runBlocking {
            routes["/big.ics"] = { it.send(200, "x".repeat(2048)) }
            val error = assertFailsWith<FetchException> { FeedFetcher(localGuard, maxBytes = 1024).fetch("http://127.0.0.1:$port/big.ics") }
            assertTrue(error.message!!.startsWith("Feed is larger than"))
        }

    @Test
    fun httpErrorsAreReported() =
        runBlocking {
            val error = assertFailsWith<FetchException> { FeedFetcher(localGuard).fetch("http://127.0.0.1:$port/missing.ics?key=hidden") }
            assertEquals("HTTP 404 from http://127.0.0.1:$port/missing.ics.", error.message)
        }
}
