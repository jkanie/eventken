package io.github.jkanie.eventken.server.support

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.jkanie.eventken.server.db.Db
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant

object Fixtures {
    val dir: File = File(System.getProperty("eventken.fixtures") ?: "../fixtures")

    fun text(path: String): String = File(dir, path).readText()

    val vancouver: ZoneId = ZoneId.of("America/Vancouver")
}

class FixedClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/**
 * 1 PostgreSQL for the whole test run. It is the database named by
 * EVENTKEN_TEST_JDBC_URL when that is set (CI uses a service container), else a
 * postgres:17-alpine container from Testcontainers.
 */
object TestDatabase {
    val dataSource: DataSource by lazy {
        val url = System.getenv("EVENTKEN_TEST_JDBC_URL")?.ifBlank { null }
        val config =
            if (url != null) {
                HikariConfig().apply {
                    jdbcUrl = url
                    username = System.getenv("EVENTKEN_TEST_DB_USER") ?: "eventken"
                    password = System.getenv("EVENTKEN_TEST_DB_PASSWORD") ?: "eventken"
                }
            } else {
                val container = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
                HikariConfig().apply {
                    jdbcUrl = container.jdbcUrl
                    username = container.username
                    password = container.password
                }
            }
        config.maximumPoolSize = 5
        HikariDataSource(config).also(Db::migrate)
    }

    /** Empties every table, so each test starts from nothing. */
    fun reset() {
        dataSource.connection.use { c ->
            c.createStatement().use { it.execute("TRUNCATE event_source, event, feed RESTART IDENTITY CASCADE") }
        }
    }
}
