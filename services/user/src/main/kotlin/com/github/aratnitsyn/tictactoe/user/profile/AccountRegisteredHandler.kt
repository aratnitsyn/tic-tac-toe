package com.github.aratnitsyn.tictactoe.user.profile

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class AccountRegisteredHandler(
    private val mapper: ObjectMapper,
) {
    @Suppress("MagicNumber") // JDBC positions and schema version.
    fun apply(
        connection: Connection,
        bytes: ByteArray,
    ) {
        val event = mapper.readTree(bytes)
        require(event.path("type").asText() == "AccountRegistered")
        require(event.path("schemaVersion").asInt() == 1)
        require(event.path("aggregateType").asText() == "ACCOUNT")
        val userId = UUID.fromString(event.path("payload").path("userId").asText())
        require(event.path("aggregateId").asText() == userId.toString())
        val registered = Instant.parse(event.path("occurredAt").asText())
        connection
            .prepareStatement(
                "INSERT INTO profiles(user_id,registered_at) VALUES (?,?) ON " +
                    "CONFLICT DO NOTHING",
            ).use {
                it.setObject(1, userId)
                it.setTimestamp(2, Timestamp.from(registered))
                it.executeUpdate()
            }
    }
}
