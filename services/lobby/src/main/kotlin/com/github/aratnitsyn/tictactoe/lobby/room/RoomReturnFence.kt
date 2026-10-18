package com.github.aratnitsyn.tictactoe.lobby.room

import java.sql.Connection
import java.util.UUID

data class RoomReturnFence(
    val continuing: Boolean,
    val participationVersion: Long?,
)

class RoomReturnStore(
    private val db: LobbyDatabase,
) {
    fun lock(
        connection: Connection,
        room: UUID,
        match: UUID,
        version: Long,
    ): RoomReturnFence? {
        db.lock(connection, "room/$room")
        val pending =
            connection
                .prepareStatement(
                    "SELECT 1 FROM room_operation WHERE room_id=? AND response IS NULL LIMIT 1",
                ).use {
                    it.setObject(1, room)
                    it.executeQuery().use { row -> row.next() }
                }
        if (pending) {
            throw LobbyFailure(
                "SERVICE_UNAVAILABLE",
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                    .value(),
            )
        }
        return connection
            .prepareStatement(
                "SELECT match_id,return_match_id,return_participation_version,source_version " +
                    "FROM room WHERE room_id=? FOR UPDATE",
            ).use {
                it.setObject(1, room)
                it.executeQuery().use { row ->
                    if (!row.next()) return@use null
                    val current = row.getObject("match_id", UUID::class.java)
                    val continuing = current == null && row.getObject("return_match_id", UUID::class.java) == match
                    val validMatch = current == match || continuing
                    if (!validMatch || row.getLong("source_version") >= version) {
                        null
                    } else {
                        RoomReturnFence(
                            continuing,
                            (row.getObject("return_participation_version") as Number?)?.toLong(),
                        )
                    }
                }
            }
    }
}
