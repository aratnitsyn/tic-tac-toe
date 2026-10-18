package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import java.sql.Connection
import java.util.UUID

class LobbyService(
    private val db: LobbyDatabase,
    private val store: RoomStore,
    private val game: GameClient,
    private val afterGame: () -> Unit = {},
) {
    fun execute(command: LobbyCommand): LobbyResponse {
        validate(command)
        val id = store.begin(command)
        return resume(id)
    }

    fun resume(id: UUID): LobbyResponse {
        val op = db.transaction { store.read(it, id) }
        if (op.response != null) return LobbyResponse(checkNotNull(op.status), op.response)
        val result = game.resolve(op)
        if (result.path("status").asText() == "PENDING") {
            throw LobbyFailure("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value())
        }
        afterGame()
        return db.transaction { connection ->
            val current = store.read(connection, id)
            if (current.response != null) {
                return@transaction LobbyResponse(checkNotNull(current.status), current.response)
            }
            db.lock(connection, "room/${op.roomId}")
            val response =
                if (result.path("status").asText() == "APPLIED") {
                    applied(connection, op, result)
                } else {
                    if (op.kind == "CREATE") {
                        db.update(
                            connection,
                            "UPDATE room SET status='CLOSED',version=version+1 WHERE room_id=?",
                            op.roomId,
                        )
                    }
                    LobbyResponse(
                        HttpStatus.CONFLICT.value(),
                        db.mapper.createObjectNode().apply {
                            val code = result.path("errorCode").asText("PARTICIPATION_CONFLICT")
                            put("code", code)
                            put("message", code)
                            put("serverTime", db.now(connection).toString())
                            put("correlationId", op.correlationId.toString())
                        },
                    )
                }
            db.update(
                connection,
                "UPDATE room_operation SET response=?::jsonb,response_status=? WHERE operation_id=?",
                response.body.toString(),
                response.status,
                id,
            )
            response
        }
    }

    fun snapshot(
        user: UUID,
        room: UUID,
    ): JsonNode =
        db.transaction { connection ->
            store.authorize(connection, room, user)
            envelope(connection, store.snapshot(connection, room))
        }

    fun pending(): List<UUID> = store.pending()

    private fun applied(
        connection: Connection,
        op: RoomOperation,
        result: JsonNode,
    ): LobbyResponse {
        val version =
            if (op.kind == "JOIN") {
                op.gamePayload["expectedVersion"].asLong() + 1
            } else {
                result["version"].asLong()
            }
        when (op.kind) {
            "CREATE" ->
                db.update(
                    connection,
                    "UPDATE room SET status='WAITING',version=version+1 WHERE room_id=? AND status='REGISTERING'",
                    op.roomId,
                )
            "JOIN" -> {
                db.update(
                    connection,
                    "INSERT INTO room_member(room_id,user_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                    op.roomId,
                    op.userId,
                )
                db.update(
                    connection,
                    "UPDATE room SET status='PREPARING',match_id=?,source_version=-1,version=version+1 WHERE room_id=?",
                    UUID.fromString(result["resourceId"].asText()),
                    op.roomId,
                )
            }
            "LEAVE" ->
                db.update(
                    connection,
                    "UPDATE room SET status='CLOSED',version=version+1 WHERE room_id=?",
                    op.roomId,
                )
        }
        val lobby = store.snapshot(connection, op.roomId)
        RoomEvent(db).emit(connection, op.roomId, lobby, op.correlationId, op.id)
        if (op.kind == "CREATE") return LobbyResponse(HttpStatus.CREATED.value(), envelope(connection, lobby))
        val participation =
            db.mapper.createObjectNode().apply {
                put("userId", op.userId.toString())
                put("version", version)
                put("state", if (op.kind == "JOIN") "IN_MATCH" else "MENU")
                if (op.kind == "JOIN") put("resourceId", result["resourceId"].asText())
            }
        return LobbyResponse(HttpStatus.OK.value(), envelope(connection, participation))
    }

    private fun envelope(
        connection: Connection,
        data: JsonNode,
    ): JsonNode =
        db.mapper.createObjectNode().apply {
            put("serverTime", db.now(connection).toString())
            put("version", data["version"].asLong())
            set<JsonNode>("data", data)
        }

    private fun invalid(): Nothing = throw LobbyFailure("VALIDATION_ERROR", HttpStatus.BAD_REQUEST.value())

    private fun validate(command: LobbyCommand) {
        val allowed = if (command.kind == "JOIN") setOf("expectedVersion", "code") else setOf("expectedVersion")
        val version = command.payload.path("expectedVersion")
        if (!command.payload.isObject) invalid()
        if (
            command.payload
                .fieldNames()
                .asSequence()
                .any { it !in allowed }
        ) {
            invalid()
        }
        if (!version.isIntegralNumber || !version.canConvertToLong() || version.asLong() < 0) invalid()
        if (command.kind == "JOIN" && !command.payload.path("code").isTextual) invalid()
        if (command.kind == "JOIN" &&
            !command.payload
                .path("code")
                .asText()
                .matches(Regex("^[A-HJ-NP-Z2-9]{10}$"))
        ) {
            invalid()
        }
    }
}
