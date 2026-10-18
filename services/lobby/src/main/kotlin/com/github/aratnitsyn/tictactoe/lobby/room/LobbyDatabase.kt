package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

// Lobby locks command/operation before room. Game calls run outside these transactions.
class LobbyDatabase(
    val source: DataSource,
    val mapper: ObjectMapper,
) {
    @Suppress("TooGenericExceptionCaught") // Every JDBC failure must roll back the whole transaction.
    fun <T> transaction(effect: (Connection) -> T): T =
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SET LOCAL lock_timeout = '3s'") }
                val result = effect(connection)
                connection.commit()
                result
            } catch (failure: Exception) {
                try {
                    connection.rollback()
                } catch (rollback: Exception) {
                    failure.addSuppressed(rollback)
                }
                throw failure
            }
        }

    fun update(
        connection: Connection,
        sql: String,
        vararg values: Any?,
    ): Int =
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    fun lock(
        connection: Connection,
        key: String,
    ) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use {
            it.setString(1, key)
            it.execute()
        }
    }

    fun now(connection: Connection): Instant =
        connection.createStatement().use {
            it.executeQuery("SELECT clock_timestamp()").use { rows ->
                rows.next()
                rows.getTimestamp(1).toInstant()
            }
        }
}
