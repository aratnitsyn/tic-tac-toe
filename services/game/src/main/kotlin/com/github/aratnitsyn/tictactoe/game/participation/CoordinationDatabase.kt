package com.github.aratnitsyn.tictactoe.game.participation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

// All operations lock operation -> sorted participants -> resource. Never reverse this order.
class CoordinationDatabase(
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

    fun participants(
        connection: Connection,
        ids: List<UUID>,
    ): List<Participation> =
        ids.distinct().sortedBy(UUID::toString).map { id ->
            update(connection, "INSERT INTO participation(user_id) VALUES (?) ON CONFLICT DO NOTHING", id)
            connection.prepareStatement("SELECT * FROM participation WHERE user_id=? FOR UPDATE").use {
                it.setObject(1, id)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    Participation(
                        id,
                        ParticipationState.valueOf(rows.getString("state")),
                        rows.getLong("version"),
                        rows.getObject("resource_id", UUID::class.java),
                        rows.getObject("operation_id", UUID::class.java),
                    )
                }
            }
        }

    fun now(connection: Connection): Instant =
        connection.createStatement().use {
            it.executeQuery("SELECT clock_timestamp()").use { rows ->
                rows.next()
                rows.getTimestamp(1).toInstant()
            }
        }

    fun participationJson(value: Participation): JsonNode =
        mapper.createObjectNode().apply {
            put("userId", value.userId.toString())
            put("state", value.state.name)
            put("version", value.version)
            value.resourceId?.let { put("resourceId", it.toString()) }
        }
}
