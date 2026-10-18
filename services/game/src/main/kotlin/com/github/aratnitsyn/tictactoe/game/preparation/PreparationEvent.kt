package com.github.aratnitsyn.tictactoe.game.preparation

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationEvent
import com.github.aratnitsyn.tictactoe.game.persistence.Outbox
import com.github.aratnitsyn.tictactoe.game.persistence.OutboxEvent
import java.sql.Connection
import java.util.UUID

class PreparationEvent(
    private val db: CoordinationDatabase,
) {
    fun emit(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        value: CoordinationEvent,
    ) {
        val type = value.type
        val aggregate = value.aggregateType
        val id = value.id
        val version = value.version
        val recipients = value.recipients
        val payload = value.payload
        val event = UUID.randomUUID()
        val envelope =
            db.mapper.createObjectNode().apply {
                put("eventId", event.toString())
                put("type", type)
                put("schemaVersion", 1)
                put("aggregateType", aggregate)
                put("aggregateId", id.toString())
                put("aggregateVersion", version)
                put("occurredAt", db.now(connection).toString())
                put("correlationId", match.correlation.toString())
                put("causationId", cause.toString())
                putArray("recipientIds").also { array -> recipients.forEach { array.add(it.toString()) } }
                set<JsonNode>("payload", payload)
            }
        Outbox.enqueue(connection, OutboxEvent(event, id, version, type, envelope))
    }
}
