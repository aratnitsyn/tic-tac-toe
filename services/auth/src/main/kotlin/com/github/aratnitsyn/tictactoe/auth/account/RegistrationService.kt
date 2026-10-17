package com.github.aratnitsyn.tictactoe.auth.account

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.persistence.Outbox
import com.github.aratnitsyn.tictactoe.auth.persistence.OutboxEvent
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class RegistrationService(
    private val source: DataSource,
    private val hasher: PasswordHasher,
    private val mapper: ObjectMapper,
) {
    @Suppress("TooGenericExceptionCaught") // Rollback must cover every transaction failure.
    fun register(
        commandId: UUID,
        credentials: Credentials,
    ): JsonNode {
        val email = CredentialPolicy.email(credentials.email)
        CredentialPolicy.password(credentials.password)
        // Expensive hashing is performed before acquiring the account lock.
        val hash = hasher.hash(credentials.password)
        return source.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use {
                    it.setString(1, "register/${email.key}")
                    it.execute()
                }
                val response =
                    existing(connection, email, commandId, credentials)
                        ?: create(connection, email, commandId, credentials.clientType, hash)
                connection.commit()
                response
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    @Suppress("MagicNumber") // JDBC positions in adjacent SQL.
    private fun existing(
        connection: Connection,
        email: EmailAddress,
        commandId: UUID,
        credentials: Credentials,
    ): JsonNode? =
        connection
            .prepareStatement(
                "SELECT a.password_hash,c.command_id,c.client_type,c.response_body FROM accounts a " +
                    "LEFT JOIN registration_commands c ON a.user_id=c.user_id AND c.command_id=? " +
                    "WHERE a.email_key=?",
            ).use {
                it.setObject(1, commandId)
                it.setString(2, email.key)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    if (rows.getObject("command_id") == null) throw AuthFailure("VALIDATION_ERROR", 409)
                    if (rows.getString("client_type") != credentials.clientType.name ||
                        !hasher.matches(credentials.password, rows.getString("password_hash"))
                    ) {
                        throw AuthFailure("IDEMPOTENCY_CONFLICT", 409)
                    }
                    mapper.readTree(rows.getString("response_body"))
                }
            }

    @Suppress("MagicNumber") // JDBC positions in adjacent SQL and aggregate initial version.
    private fun create(
        connection: Connection,
        email: EmailAddress,
        commandId: UUID,
        clientType: ClientType,
        hash: String,
    ): JsonNode {
        val userId = UUID.randomUUID()
        val now =
            connection.createStatement().use {
                it.executeQuery("SELECT clock_timestamp()").use { r ->
                    r.next()
                    r.getTimestamp(1).toInstant()
                }
            }
        connection
            .prepareStatement(
                "INSERT INTO accounts(user_id,email_original,email_key,password_hash) VALUES (?,?,?,?)",
            ).use {
                it.setObject(1, userId)
                it.setString(2, email.original)
                it.setString(3, email.key)
                it.setString(4, hash)
                it.executeUpdate()
            }
        val data =
            mapper
                .createObjectNode()
                .put("userId", userId.toString())
                .put("email", email.original)
                .put("emailVerified", false)
        val response =
            mapper
                .createObjectNode()
                .put("serverTime", now.toString())
                .put("version", 1)
                .set<JsonNode>("data", data)
        connection
            .prepareStatement(
                "INSERT INTO registration_commands(email_key,command_id,user_id,client_type,response_body) " +
                    "VALUES (?,?,?,?,?::jsonb)",
            ).use {
                it.setString(1, email.key)
                it.setObject(2, commandId)
                it.setObject(3, userId)
                it.setString(4, clientType.name)
                it.setString(5, response.toString())
                it.executeUpdate()
            }
        publishRegistered(connection, userId, commandId, now)
        return response
    }

    private fun publishRegistered(
        connection: Connection,
        userId: UUID,
        commandId: UUID,
        now: java.time.Instant,
    ) {
        val eventId = UUID.randomUUID()
        val payload = mapper.createObjectNode().put("userId", userId.toString())
        val envelope =
            mapper
                .createObjectNode()
                .put("eventId", eventId.toString())
                .put("type", "AccountRegistered")
                .put("aggregateType", "ACCOUNT")
                .put("schemaVersion", 1)
                .put("aggregateId", userId.toString())
                .put("aggregateVersion", 1)
                .put("occurredAt", now.toString())
                .put("correlationId", commandId.toString())
                .put("causationId", commandId.toString())
                .set<JsonNode>("payload", payload)
        (envelope as com.fasterxml.jackson.databind.node.ObjectNode).putArray("recipientIds")
        Outbox.enqueue(connection, OutboxEvent(eventId, userId, 1, "AccountRegistered", envelope))
    }
}
