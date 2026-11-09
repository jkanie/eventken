package io.github.jkanie.eventken.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.jkanie.eventken.server.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource
import kotlin.time.Instant

/**
 * A thin layer over JDBC. The queries that matter here (full-text ranking,
 * haversine distance, array overlap, advisory locks) are PostgreSQL SQL, so
 * they stay as SQL text and this class only opens connections and transactions.
 */
class Db(
    val dataSource: DataSource,
) {
    suspend fun <T> read(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.isReadOnly = true
                block(connection)
            }
        }

    suspend fun <T> transaction(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    block(connection).also { connection.commit() }
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    companion object {
        fun pool(config: ServerConfig): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl
                    username = config.dbUser
                    password = config.dbPassword
                    maximumPoolSize = 10
                    poolName = "eventken"
                },
            )

        /** Applies the SQL files in db/migration that the database has not seen yet. */
        fun migrate(dataSource: DataSource) {
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}

fun Connection.prepare(
    sql: String,
    vararg params: Any?,
): PreparedStatement = prepareStatement(sql).also { it.bindAll(params.toList()) }

fun PreparedStatement.bindAll(params: List<Any?>) {
    params.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> {
                setNull(position, Types.NULL)
            }

            is Instant -> {
                setTimestamp(position, Timestamp.from(java.time.Instant.ofEpochMilli(value.toEpochMilliseconds())))
            }

            is String -> {
                setString(position, value)
            }

            is Long -> {
                setLong(position, value)
            }

            is Int -> {
                setInt(position, value)
            }

            is Double -> {
                setDouble(position, value)
            }

            is Boolean -> {
                setBoolean(position, value)
            }

            is SqlArray -> {
                setArray(position, connection.createArrayOf("text", value.values.toTypedArray()))
            }

            is SqlJson -> {
                setObject(
                    position,
                    org.postgresql.util.PGobject().apply {
                        type = "jsonb"
                        this.value = value.json
                    },
                )
            }

            else -> {
                setObject(position, value)
            }
        }
    }
}

/** A text[] parameter. */
data class SqlArray(
    val values: List<String>,
)

/** A jsonb parameter. */
data class SqlJson(
    val json: String,
)

fun <T> PreparedStatement.list(map: (ResultSet) -> T): List<T> =
    use { statement ->
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> PreparedStatement.single(map: (ResultSet) -> T): T? = list(map).firstOrNull()

fun PreparedStatement.update(): Int = use { it.executeUpdate() }

fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.let { Instant.fromEpochMilliseconds(it.toInstant().toEpochMilli()) }

fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

fun ResultSet.textArray(column: String): List<String> = (getArray(column)?.array as? Array<*>)?.map { it.toString() } ?: emptyList()
