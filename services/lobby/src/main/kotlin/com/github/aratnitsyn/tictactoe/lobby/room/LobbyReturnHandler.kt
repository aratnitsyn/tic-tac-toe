package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.util.UUID

// Inbox and room changes commit together; Game remains authoritative for membership.
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
        val fence = RoomReturnStore(db).lock(connection, room, match, event["aggregateVersion"].asLong())
        if (fence != null) {
            val actual =
                payload["participants"]
                    .map { UUID.fromString(it["userId"].asText()) }
                    .associateWith { game.participation(it)["data"] }
            val present =
                actual.filterValues {
                    it["state"].asText() == "LOBBY" &&
                        it.path("resourceId").asText() == room.toString()
                }
            val advanced =
                fence.continuing &&
                    present.values.any {
                        it["version"].asLong() != fence.participationVersion
                    }
            if (!advanced) {
                check(present.size <= 1) { "Cancellation cannot return two players to an inviting room" }
                restore(connection, event, present)
                RoomEvent(db).emit(
                    connection,
                    room,
                    store.snapshot(connection, room),
                    UUID.fromString(event["correlationId"].asText()),
                    UUID.fromString(event["eventId"].asText()),
                )
            }
        }
    }

    private fun restore(
        connection: Connection,
        event: JsonNode,
        returned: Map<UUID, JsonNode>,
    ) {
        val payload = event["payload"]
        val room = UUID.fromString(payload["sourceRef"].asText())
        if (returned.isEmpty()) {
            db.update(
                connection,
                "UPDATE room SET status='CLOSED',version=version+1,source_version=? WHERE room_id=?",
                event["aggregateVersion"].asLong(),
                room,
            )
        } else {
            val user = returned.keys.single()
            store.rotate(connection, room)
            db.update(connection, "DELETE FROM room_member WHERE room_id=?", room)
            db.update(connection, "INSERT INTO room_member(room_id,user_id) VALUES (?,?)", room, user)
            db.update(
                connection,
                "UPDATE room SET owner_id=?,status='WAITING',match_id=NULL,source_version=?,return_match_id=?," +
                    "return_participation_version=?,version=version+1 WHERE room_id=?",
                user,
                event["aggregateVersion"].asLong(),
                UUID.fromString(payload["matchId"].asText()),
                returned.getValue(user)["version"].asLong(),
                room,
            )
        }
    }
}
