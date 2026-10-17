package com.github.aratnitsyn.tictactoe.auth.account

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class LogoutOperation(
    val operationId: UUID,
    val userId: UUID,
    val sessionId: UUID,
    val completed: Boolean,
)

fun interface ParticipationLogout {
    // Only return true for authoritative APPLIED; unknown outcomes remain CLOSING.
    fun complete(operation: LogoutOperation): Boolean
}

class LogoutService(
    private val source: DataSource,
    private val participation: ParticipationLogout,
) {
    @Suppress("MagicNumber") // JDBC positions and HTTP status.
    fun begin(
        token: String,
        commandId: UUID,
    ): LogoutOperation =
        transaction { connection ->
            connection.prepareStatement("SELECT * FROM session_slots WHERE token_hash=? FOR UPDATE").use {
                it.setString(1, tokenHash(token))
                it.executeQuery().use { rows ->
                    if (!rows.next()) throw AuthFailure("UNAUTHENTICATED", 401)
                    val userId = rows.getObject("user_id", UUID::class.java)
                    val sessionId = rows.getObject("session_id", UUID::class.java)
                    val existing = find(connection, sessionId)
                    if (existing != null) return@use existing
                    val now = databaseTime(connection)
                    val unexpired = now.isBefore(rows.getTimestamp("expires_at").toInstant())
                    if (rows.getString("state") != "ACTIVE" || !unexpired) {
                        throw AuthFailure("UNAUTHENTICATED", 401)
                    }
                    val operation = LogoutOperation(UUID.randomUUID(), userId, sessionId, false)
                    connection
                        .prepareStatement(
                            "INSERT INTO " +
                                "logout_operations(operation_id,user_id,session_id,command_id) VALUES (?,?,?,?)",
                        ).use { insert ->
                            insert.setObject(1, operation.operationId)
                            insert.setObject(2, userId)
                            insert.setObject(3, sessionId)
                            insert.setObject(4, commandId)
                            insert.executeUpdate()
                        }
                    connection
                        .prepareStatement(
                            "UPDATE session_slots SET state='CLOSING' WHERE " +
                                "user_id=?",
                        ).use { update ->
                            update.setObject(1, userId)
                            update.executeUpdate()
                        }
                    operation
                }
            }
        }

    fun reconcile(operation: LogoutOperation): Boolean {
        if (operation.completed || !participation.complete(operation)) return operation.completed
        return transaction { connection ->
            connection
                .prepareStatement(
                    "UPDATE session_slots SET state='CLOSED' WHERE session_id=? AND " +
                        "state='CLOSING'",
                ).use {
                    it.setObject(1, operation.sessionId)
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    "UPDATE logout_operations SET completed_at=clock_timestamp() " +
                        "WHERE operation_id=?",
                ).use {
                    it.setObject(1, operation.operationId)
                    it.executeUpdate()
                }
            true
        }
    }

    fun pending(): List<LogoutOperation> =
        source.connection.use { connection ->
            connection.createStatement().use {
                it
                    .executeQuery(
                        "SELECT * FROM logout_operations WHERE completed_at IS NULL ORDER " +
                            "BY created_at LIMIT 100",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    LogoutOperation(
                                        rows.getObject("operation_id", UUID::class.java),
                                        rows.getObject("user_id", UUID::class.java),
                                        rows.getObject("session_id", UUID::class.java),
                                        false,
                                    ),
                                )
                            }
                        }
                    }
            }
        }

    private fun find(
        connection: Connection,
        sessionId: UUID,
    ): LogoutOperation? =
        connection
            .prepareStatement(
                "SELECT * FROM logout_operations WHERE session_id=?",
            ).use {
                it.setObject(1, sessionId)
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        LogoutOperation(
                            rows.getObject("operation_id", UUID::class.java),
                            rows.getObject("user_id", UUID::class.java),
                            sessionId,
                            rows.getTimestamp("completed_at") != null,
                        )
                    } else {
                        null
                    }
                }
            }

    @Suppress("TooGenericExceptionCaught") // Roll back every transaction failure.
    private fun <T> transaction(effect: (Connection) -> T): T =
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                effect(connection).also { connection.commit() }
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
}
