package com.github.aratnitsyn.tictactoe.lobby.room

import java.sql.Connection
import java.util.UUID

@Suppress("MagicNumber") // HTTP unavailable status.
class LobbyLogoutHandler(
    private val db: LobbyDatabase,
    private val store: RoomStore,
    private val game: GameClient,
) {
    fun apply(
        connection: Connection,
        bytes: ByteArray,
    ) {
        val event = db.mapper.readTree(bytes)
        val menu = event.path("payload").path("state").asText() == "MENU"
        if (event.path("type").asText() != "ParticipationChanged" || !menu) return
        val user = UUID.fromString(event["aggregateId"].asText())
        val rooms =
            connection
                .prepareStatement(
                    "SELECT r.room_id FROM room r JOIN room_member m USING(room_id) " +
                        "WHERE m.user_id=? AND r.status IN ('REGISTERING','WAITING')",
                ).use {
                    it.setObject(1, user)
                    it.executeQuery().use { row ->
                        buildList { while (row.next()) add(row.getObject(1, UUID::class.java)) }
                    }
                }
        rooms.forEach { room ->
            db.lock(connection, "room/$room")
            val pending =
                connection.prepareStatement("SELECT 1 FROM room_operation WHERE room_id=? AND response IS NULL").use {
                    it.setObject(1, room)
                    it.executeQuery().use { row -> row.next() }
                }
            if (pending) throw LobbyFailure("SERVICE_UNAVAILABLE", 503)
            // Recheck ownership after the lock: a new room must not keep an abandoned old code open.
            val actual = game.participation(user)["data"]
            val current = actual["version"].asLong() >= event["aggregateVersion"].asLong()
            val ownsRoom = actual["state"].asText() == "LOBBY" && actual.path("resourceId").asText() == room.toString()
            if (!ownsRoom && current) {
                val changed =
                    db.update(
                        connection,
                        "UPDATE room SET status='CLOSED',version=version+1 WHERE room_id=? AND status='WAITING'",
                        room,
                    )
                if (changed ==
                    1
                ) {
                    RoomEvent(
                        db,
                    ).emit(
                        connection,
                        room,
                        store.snapshot(connection, room),
                        UUID.fromString(event["correlationId"].asText()),
                        UUID.fromString(event["eventId"].asText()),
                    )
                }
            }
        }
    }
}
