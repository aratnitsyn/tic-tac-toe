package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.util.UUID

// Stage 7 must supply the real cancellation and restored authoritative Game room registration.
class LobbyReturnHandler(
    private val db: LobbyDatabase,
    private val store: RoomStore,
    private val game: GameClient,
) {
    fun apply(
        connection: Connection,
        bytes: ByteArray,
    ) {
        val event = db.mapper.readTree(bytes)
        val payload = event.path("payload")
        if (event.path("type").asText() != "MatchCancelled" || payload.path("source").asText() != "LOBBY") return
        val room = UUID.fromString(payload["sourceRef"].asText())
        val match = UUID.fromString(payload["matchId"].asText())
        val returned =
            payload["participants"].map { UUID.fromString(it["userId"].asText()) }.filter { user ->
                val state = game.participation(user)["data"]
                state["state"].asText() == "LOBBY" && state.path("resourceId").asText() == room.toString()
            }
        check(returned.size <= 1) { "Cancellation cannot return two players to an inviting room" }
        db.lock(connection, "room/$room")
        val current =
            connection.prepareStatement("SELECT match_id,source_version FROM room WHERE room_id=? FOR UPDATE").use {
                it.setObject(1, room)
                it.executeQuery().use { row ->
                    row.next() && row.getObject("match_id", UUID::class.java) == match &&
                        row.getLong("source_version") < event["aggregateVersion"].asLong()
                }
            }
        if (!current) return
        if (returned.isEmpty()) {
            db.update(
                connection,
                "UPDATE room SET status='CLOSED',version=version+1,source_version=? WHERE room_id=?",
                event["aggregateVersion"].asLong(),
                room,
            )
        } else {
            store.rotate(connection, room)
            db.update(connection, "DELETE FROM room_member WHERE room_id=?", room)
            db.update(connection, "INSERT INTO room_member(room_id,user_id) VALUES (?,?)", room, returned.single())
            db.update(
                connection,
                "UPDATE room SET " +
                    "owner_id=?,status='WAITING',match_id=NULL,source_version=?,version=version+1 WHERE room_id=?",
                returned.single(),
                event["aggregateVersion"].asLong(),
                room,
            )
        }
        RoomEvent(db).emit(
            connection,
            room,
            store.snapshot(connection, room),
            UUID.fromString(event["correlationId"].asText()),
            UUID.fromString(event["eventId"].asText()),
        )
    }
}
