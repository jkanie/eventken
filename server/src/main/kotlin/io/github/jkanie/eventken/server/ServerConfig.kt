package io.github.jkanie.eventken.server

import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Every setting comes from an environment variable, so no secret has to live in a file. */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val jdbcUrl: String = "jdbc:postgresql://localhost:5432/eventken",
    val dbUser: String = "eventken",
    val dbPassword: String = "eventken",
    /** Null turns the admin endpoints off: they answer 503. */
    val adminToken: String? = null,
    /** Lets feeds point at private, loopback, link-local, reserved addresses. For local work and tests only. */
    val allowPrivateTargets: Boolean = false,
    /** Null turns the scheduled ingest off. Admins can still start an ingest by hand. */
    val ingestInterval: Duration? = 60.minutes,
    val fetchConcurrency: Int = 4,
    /** The zone for times that a feed gives without 1, such as DTSTART:20270701T190000. */
    val defaultZone: ZoneId = ZoneId.of("UTC"),
) {
    companion object {
        const val MIN_ADMIN_TOKEN_LENGTH = 24

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            fun value(name: String): String? = env[name]?.trim()?.ifEmpty { null }

            val token = value("EVENTKEN_ADMIN_TOKEN")
            require(token == null || token.length >= MIN_ADMIN_TOKEN_LENGTH) {
                "EVENTKEN_ADMIN_TOKEN must be at least $MIN_ADMIN_TOKEN_LENGTH characters, so it cannot be guessed. " +
                    "Leave it empty to turn the admin endpoints off."
            }
            val interval = value("EVENTKEN_INGEST_INTERVAL_MINUTES")?.toLong() ?: 60
            require(interval >= 0) { "EVENTKEN_INGEST_INTERVAL_MINUTES must be 0 (off) or more." }
            val defaults = ServerConfig()
            return ServerConfig(
                host = value("EVENTKEN_HOST") ?: defaults.host,
                port = value("EVENTKEN_PORT")?.toInt() ?: defaults.port,
                jdbcUrl = value("EVENTKEN_DB_URL") ?: defaults.jdbcUrl,
                dbUser = value("EVENTKEN_DB_USER") ?: defaults.dbUser,
                dbPassword = value("EVENTKEN_DB_PASSWORD") ?: defaults.dbPassword,
                adminToken = token,
                allowPrivateTargets = value("ALLOW_PRIVATE_TARGETS")?.lowercase() in setOf("1", "true", "yes", "on"),
                ingestInterval = if (interval == 0L) null else interval.minutes,
                fetchConcurrency = value("EVENTKEN_FETCH_CONCURRENCY")?.toInt()?.coerceIn(1, 32) ?: defaults.fetchConcurrency,
                defaultZone = value("EVENTKEN_DEFAULT_TIME_ZONE")?.let(ZoneId::of) ?: defaults.defaultZone,
            )
        }
    }
}
