package com.github.aratnitsyn.tictactoe.lobby.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599

class IdempotencyConflict : IllegalStateException("IDEMPOTENCY_CONFLICT")

data class CommandResponse(
    val status: Int,
    val body: JsonNode,
)

private data class CommandKey(
    val userId: UUID,
    val operationType: String,
    val commandId: UUID,
)

class CommandStore(
    private val dataSource: DataSource,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    // A transaction boundary must rollback and rethrow every callback failure.
    @Suppress("TooGenericExceptionCaught")
    fun execute(
        userId: UUID,
        operationType: String,
        commandId: UUID,
        payload: JsonNode,
        effect: (Connection) -> CommandResponse,
    ): CommandResponse {
        require(operationType.isNotBlank())
        val key = CommandKey(userId, operationType, commandId)
        val payloadHash = sha256(canonical(payload).toString())
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lock(connection, "$userId/$operationType/$commandId")
                val existing = read(connection, key, payloadHash)
                val response =
                    existing ?: effect(connection).also {
                        require(it.status in MIN_HTTP_STATUS..MAX_HTTP_STATUS)
                        save(connection, key, payloadHash, it)
                    }
                connection.commit()
                response
            } catch (failure: Exception) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
    }

    private fun lock(
        connection: Connection,
        key: String,
    ) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
            it.setString(1, key)
            it.execute()
        }
    }

    // Numeric literals are JDBC parameter positions in the SQL immediately below.
    @Suppress("MagicNumber")
    private fun read(
        connection: Connection,
        key: CommandKey,
        payloadHash: String,
    ): CommandResponse? =
        connection
            .prepareStatement(
                "SELECT payload_hash, response_status, response_body FROM command_results " +
                    "WHERE user_id = ? AND operation_type = ? AND command_id = ?",
            ).use { statement ->
                statement.setObject(1, key.userId)
                statement.setString(2, key.operationType)
                statement.setObject(3, key.commandId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    if (rows.getString("payload_hash") != payloadHash) throw IdempotencyConflict()
                    CommandResponse(rows.getInt("response_status"), mapper.readTree(rows.getString("response_body")))
                }
            }

    // Numeric literals are JDBC parameter positions in the SQL immediately below.
    @Suppress("MagicNumber")
    private fun save(
        connection: Connection,
        key: CommandKey,
        payloadHash: String,
        response: CommandResponse,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO command_results " +
                    "(user_id, operation_type, command_id, payload_hash, response_status, response_body) " +
                    "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
            ).use {
                it.setObject(1, key.userId)
                it.setString(2, key.operationType)
                it.setObject(3, key.commandId)
                it.setString(4, payloadHash)
                it.setInt(5, response.status)
                it.setString(6, response.body.toString())
                it.executeUpdate()
            }
    }

    private fun canonical(node: JsonNode): JsonNode =
        when {
            node.isObject ->
                mapper.createObjectNode().also { result ->
                    node
                        .fieldNames()
                        .asSequence()
                        .sorted()
                        .forEach { key -> result.set<JsonNode>(key, canonical(node[key])) }
                }
            node.isArray -> mapper.createArrayNode().also { result -> node.forEach { result.add(canonical(it)) } }
            else -> node
        }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
