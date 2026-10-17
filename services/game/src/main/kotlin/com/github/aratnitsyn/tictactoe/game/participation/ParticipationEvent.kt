package com.github.aratnitsyn.tictactoe.game.participation

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.persistence.Outbox
import com.github.aratnitsyn.tictactoe.game.persistence.OutboxEvent
import java.sql.Connection
import java.util.UUID

class ParticipationEvent(
    private val db: CoordinationDatabase,
) {
    fun emit(
        connection: Connection,
        op: CoordinationOperation,
        event: CoordinationEvent,
    ) {
        val type = event.type
        val aggregateType = event.aggregateType
        val id = event.id
        val version = event.version
        val recipients = event.recipients
        val payload = event.payload
        val eventId = UUID.randomUUID()
        val envelope =
            db.mapper.createObjectNode().apply {
                put("eventId", eventId.toString())
                put("type", type)
                put("schemaVersion", 1)
                put("aggregateType", aggregateType)
                put("aggregateId", id.toString())
                put("aggregateVersion", version)
                put("occurredAt", db.now(connection).toString())
                put("correlationId", op.correlationId.toString())
                put("causationId", op.id.toString())
                putArray("recipientIds").also { array -> recipients.forEach { array.add(it.toString()) } }
                set<JsonNode>("payload", payload)
            }
        Outbox.enqueue(connection, OutboxEvent(eventId, id, version, type, envelope))
    }
}

data class CoordinationEvent(
    val type: String,
    val aggregateType: String,
    val id: UUID,
    val version: Long,
    val recipients: List<UUID>,
    val payload: JsonNode,
)
