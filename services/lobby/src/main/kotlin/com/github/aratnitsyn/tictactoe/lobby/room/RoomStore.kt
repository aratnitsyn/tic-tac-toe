package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import java.sql.Connection
import java.util.UUID

@Suppress("MagicNumber") // JDBC parameter positions match the SQL column order.
class RoomStore(
    private val db: LobbyDatabase,
    private val code: () -> String,
) {
    fun begin(command: LobbyCommand): UUID =
        db.transaction { connection ->
            db.lock(connection, "command/${command.userId}/${command.kind}/${command.commandId}")
            val previous = previous(connection, command)
            if (previous != null) return@transaction previous
            val id = UUID.randomUUID()
            val room =
                when (command.kind) {
                    "CREATE" -> create(connection, command.userId)
                    "JOIN" -> findCode(connection, command.payload["code"].asText())
                    else -> command.roomId ?: throw LobbyFailure("VALIDATION_ERROR", HttpStatus.BAD_REQUEST.value())
                }
            if (command.kind == "LEAVE") authorize(connection, room, command.userId)
            val game =
                db.mapper.createObjectNode().apply {
                    put("operationId", id.toString())
                    put("expectedVersion", command.payload["expectedVersion"].asLong())
                    if (command.kind == "CREATE") {
                        put("roomId", room.toString())
                        put("ownerId", command.userId.toString())
                    } else {
                        put("userId", command.userId.toString())
                        put("target", if (command.kind == "JOIN") "LOBBY" else "MENU")
                        put("resourceId", room.toString())
                    }
                }
            db.update(
                connection,
                "INSERT INTO " +
                    "room_operation(operation_id,user_id,command_id,kind,payload," +
                    "room_id,game_payload,correlation_id) " +
                    "VALUES (?,?,?,?,?::jsonb,?,?::jsonb,?)",
                id,
                command.userId,
                command.commandId,
                command.kind,
                command.payload.toString(),
                room,
                game.toString(),
                command.correlationId,
            )
            id
        }

    private fun previous(
        connection: Connection,
        command: LobbyCommand,
    ): UUID? =
        connection
            .prepareStatement(
                "SELECT operation_id,payload,room_id FROM room_operation WHERE " +
                    "user_id=? AND kind=? AND command_id=?",
            ).use {
                it.setObject(1, command.userId)
                it.setString(2, command.kind)
                it.setObject(3, command.commandId)
                it.executeQuery().use { row ->
                    if (!row.next()) return@use null
                    val changedPayload =
                        db.mapper.readTree(row.getString("payload")) !=
                            db.mapper.readTree(command.payload.toString())
                    if (changedPayload ||
                        (command.roomId != null && row.getObject("room_id", UUID::class.java) != command.roomId)
                    ) {
                        throw LobbyFailure("IDEMPOTENCY_CONFLICT")
                    }
                    row.getObject("operation_id", UUID::class.java)
                }
            }

    fun read(
        connection: Connection,
        id: UUID,
    ): RoomOperation =
        connection
            .prepareStatement(
                "SELECT * FROM room_operation WHERE operation_id=? FOR UPDATE",
            ).use {
                it.setObject(1, id)
                it.executeQuery().use { row ->
                    if (!row.next()) throw LobbyFailure("NOT_FOUND", HttpStatus.NOT_FOUND.value())
                    RoomOperation(
                        id,
                        row.getObject("user_id", UUID::class.java),
                        row.getString("kind"),
                        row.getObject("room_id", UUID::class.java),
                        db.mapper.readTree(row.getString("game_payload")),
                        row.getObject("correlation_id", UUID::class.java),
                        row.getString("response")?.let(db.mapper::readTree),
                        row.getObject("response_status") as Int?,
                    )
                }
            }

    fun authorize(
        connection: Connection,
        room: UUID,
        user: UUID,
    ) {
        val exists =
            connection.prepareStatement("SELECT 1 FROM room_member WHERE room_id=? AND user_id=?").use {
                it.setObject(1, room)
                it.setObject(2, user)
                it.executeQuery().use { row -> row.next() }
            }
        if (!exists) throw LobbyFailure("NOT_FOUND", HttpStatus.NOT_FOUND.value())
    }

    fun snapshot(
        connection: Connection,
        id: UUID,
    ): JsonNode =
        connection.prepareStatement("SELECT * FROM room WHERE room_id=?").use {
            it.setObject(1, id)
            it.executeQuery().use { row ->
                if (!row.next() ||
                    row.getString("status") == "REGISTERING"
                ) {
                    throw LobbyFailure("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value())
                }
                val players = players(connection, id)
                db.mapper.createObjectNode().apply {
                    put("lobbyId", id.toString())
                    put("code", row.getString("code"))
                    put("status", row.getString("status"))
                    put("version", row.getLong("version"))
                    set<JsonNode>("players", players)
                }
            }
        }

    private fun players(
        connection: Connection,
        id: UUID,
    ): JsonNode =
        connection.prepareStatement("SELECT user_id FROM room_member WHERE room_id=? ORDER BY user_id").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { members ->
                db.mapper.createArrayNode().also { array -> while (members.next()) array.add(members.getString(1)) }
            }
        }

    fun pending(): List<UUID> =
        db.source.connection.use { connection ->
            connection.createStatement().use {
                it
                    .executeQuery(
                        "SELECT operation_id FROM room_operation WHERE response IS NULL " +
                            "ORDER BY created_at LIMIT 100",
                    ).use { row ->
                        buildList { while (row.next()) add(row.getObject(1, UUID::class.java)) }
                    }
            }
        }

    fun rotate(
        connection: Connection,
        room: UUID,
    ) {
        repeat(CODE_ATTEMPTS) {
            val savepoint = connection.setSavepoint()
            try {
                val next = code()
                val changed =
                    db.update(
                        connection,
                        "UPDATE room SET code=? WHERE room_id=? AND code<>?",
                        next,
                        room,
                        next,
                    )
                connection.releaseSavepoint(savepoint)
                if (changed == 1) return
            } catch (failure: java.sql.SQLException) {
                if (failure.sqlState != "23505") throw failure
                connection.rollback(savepoint)
            }
        }
        throw LobbyFailure("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value())
    }

    private fun create(
        connection: Connection,
        owner: UUID,
    ): UUID {
        val room = UUID.randomUUID()
        repeat(CODE_ATTEMPTS) {
            val inserted =
                db.update(
                    connection,
                    "INSERT INTO room(room_id,owner_id,code,status) VALUES " +
                        "(?,?,?,'REGISTERING') ON CONFLICT(code) DO NOTHING",
                    room,
                    owner,
                    code(),
                )
            if (inserted == 1) {
                db.update(connection, "INSERT INTO room_member(room_id,user_id) VALUES (?,?)", room, owner)
                return room
            }
        }
        throw LobbyFailure("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value())
    }

    private fun findCode(
        connection: Connection,
        value: String,
    ): UUID =
        connection
            .prepareStatement(
                "SELECT room_id FROM room WHERE code=? AND status='WAITING'",
            ).use {
                it.setString(1, value)
                it.executeQuery().use { row ->
                    if (!row.next()) throw LobbyFailure("NOT_FOUND", HttpStatus.NOT_FOUND.value())
                    row.getObject(1, UUID::class.java)
                }
            }
}

private const val CODE_ATTEMPTS = 10
