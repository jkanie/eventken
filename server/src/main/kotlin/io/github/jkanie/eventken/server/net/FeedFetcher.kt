package io.github.jkanie.eventken.server.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

sealed interface FetchResult {
    data class Fetched(
        val body: String,
        val finalUrl: String,
        val etag: String?,
        val lastModified: String?,
    ) : FetchResult

    /** The server answered 304: the feed has not changed since the last fetch. */
    data object NotModified : FetchResult
}

class FetchException(
    message: String,
) : IOException(message)

/**
 * Fetches feeds politely and safely: a User-Agent that names the project,
 * robots.txt obeyed, a size limit, conditional requests, at most 5 redirects.
 * Redirects are followed by hand, so each hop passes the [TargetGuard] and the
 * robots.txt of its own site.
 */
class FeedFetcher(
    private val guard: TargetGuard,
    private val userAgent: String = USER_AGENT,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val robotsTtl: Duration = 24.hours,
    private val clock: Clock = Clock.System,
    private val client: OkHttpClient = defaultClient(guard),
) {
    private data class CachedRobots(
        val robots: RobotsTxt,
        val fetchedAt: Instant,
    )

    private val robotsCache = ConcurrentHashMap<String, CachedRobots>()

    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
    ): FetchResult =
        withContext(Dispatchers.IO) {
            var current = guard.checkUrl(url)
            repeat(MAX_REDIRECTS + 1) {
                if (!robotsFor(current).allows(current.encodedPath + (current.encodedQuery?.let { "?$it" } ?: ""))) {
                    throw FetchException("robots.txt of ${current.host} does not allow ${current.encodedPath}.")
                }
                val request =
                    Request
                        .Builder()
                        .url(current)
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/calendar, application/ld+json, text/html;q=0.9, */*;q=0.5")
                        .apply {
                            etag?.let { header("If-None-Match", it) }
                            lastModified?.let { header("If-Modified-Since", it) }
                        }.build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isRedirect -> {
                            val location =
                                response.header("Location")?.let { current.resolve(it) }
                                    ?: throw FetchException("Redirect from ${current.redacted()} has no usable Location.")
                            current = guard.checkUrl(location.toString())
                        }

                        response.code == 304 -> {
                            return@withContext FetchResult.NotModified
                        }

                        response.isSuccessful -> {
                            return@withContext FetchResult.Fetched(
                                body = readLimited(response),
                                finalUrl = current.toString(),
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                            )
                        }

                        else -> {
                            throw FetchException("HTTP ${response.code} from ${current.redacted()}.")
                        }
                    }
                }
            }
            throw FetchException("More than $MAX_REDIRECTS redirects from $url.")
        }

    /** RFC 9309: a missing file (4xx) allows everything, an unreadable 1 (5xx, network error) allows nothing. */
    private fun robotsFor(url: HttpUrl): RobotsTxt {
        val origin = "${url.scheme}://${url.host}:${url.port}"
        robotsCache[origin]?.takeIf { clock.now() - it.fetchedAt < robotsTtl }?.let { return it.robots }
        val robots =
            try {
                var target = guard.checkUrl("$origin/robots.txt")
                var result: RobotsTxt? = null
                var hops = 0
                while (result == null) {
                    val request =
                        Request
                            .Builder()
                            .url(target)
                            .header("User-Agent", userAgent)
                            .build()
                    client.newCall(request).execute().use { response ->
                        when {
                            response.isRedirect && hops < MAX_REDIRECTS -> {
                                hops++
                                target =
                                    guard.checkUrl(
                                        response.header("Location")?.let { target.resolve(it) }?.toString()
                                            ?: throw FetchException("robots.txt redirect without Location"),
                                    )
                            }

                            response.isSuccessful -> {
                                result =
                                    RobotsTxt.parse(readLimited(response, RobotsTxt.MAX_BYTES.toLong(), truncate = true), PRODUCT_TOKEN)
                            }

                            response.code in 400..499 -> {
                                result = RobotsTxt.ALLOW_ALL
                            }

                            else -> {
                                result = RobotsTxt.DISALLOW_ALL
                            }
                        }
                    }
                }
                result
            } catch (e: TargetRefusedException) {
                throw e
            } catch (_: IOException) {
                RobotsTxt.DISALLOW_ALL
            }
        robotsCache[origin] = CachedRobots(robots, clock.now())
        return robots
    }

    private fun readLimited(
        response: Response,
        limit: Long = maxBytes,
        truncate: Boolean = false,
    ): String {
        val body = response.body
        val source = body.source()
        source.request(limit + 1)
        val buffer = source.buffer
        if (buffer.size > limit && !truncate) throw FetchException("Feed is larger than $limit bytes.")
        val charset = body.contentType()?.charset() ?: Charsets.UTF_8
        return buffer.readString(minOf(buffer.size, limit), charset)
    }

    companion object {
        const val PRODUCT_TOKEN = "eventken"
        const val USER_AGENT = "eventken/0.1 (+https://github.com/jkanie/eventken)"
        const val MAX_REDIRECTS = 5

        fun defaultClient(guard: TargetGuard): OkHttpClient =
            OkHttpClient
                .Builder()
                .dns(guard.dns)
                // A proxy would resolve names itself and skip the guard, so feeds are fetched directly.
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
