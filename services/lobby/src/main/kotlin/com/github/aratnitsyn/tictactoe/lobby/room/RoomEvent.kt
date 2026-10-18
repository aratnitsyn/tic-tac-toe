package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.lobby.persistence.Outbox
import com.github.aratnitsyn.tictactoe.lobby.persistence.OutboxEvent
import java.sql.Connection
import java.util.UUID

class RoomEvent(
    private val db: LobbyDatabase,
) {
    fun emit(
        connection: Connection,
        room: UUID,
        lobby: JsonNode,
        correlation: UUID,
        causation: UUID,
    ) {
        val id = UUID.randomUUID()
        val version = lobby["version"].asLong()
        val event =
            db.mapper.createObjectNode().apply {
                put("eventId", id.toString())
                put("type", "LobbyChanged")
                put("schemaVersion", 1)
                put("aggregateType", "LOBBY")
                put("aggregateId", room.toString())
                put("aggregateVersion", version)
                put("occurredAt", db.now(connection).toString())
                put("correlationId", correlation.toString())
                put("causationId", causation.toString())
                set<JsonNode>("recipientIds", lobby["players"])
                set<JsonNode>("payload", lobby)
            }
        Outbox.enqueue(connection, OutboxEvent(id, room, version, "LobbyChanged", event))
    }
}
