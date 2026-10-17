package com.github.aratnitsyn.tictactoe.game.participation

import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.util.UUID

data class CoordinationOperation(
    val id: UUID,
    val kind: String,
    val payload: JsonNode,
    val correlationId: UUID,
    val candidateId: UUID,
    val status: String,
    val result: JsonNode?,
    val errorCode: String?,
)

class OperationStore(
    private val db: CoordinationDatabase,
    private val beforeCommit: () -> Unit = {},
) {
    fun begin(
        kind: String,
        payload: JsonNode,
        correlationId: UUID,
    ): UUID =
        db.transaction { connection ->
            val id = UUID.fromString(payload["operationId"].asText())
            db.lock(connection, "operation/$id")
            val previous = read(connection, id)
            if (previous != null) {
                if (previous.kind != kind ||
                    previous.payload != db.mapper.readTree(payload.toString())
                ) {
                    throw CoordinationFailure("IDEMPOTENCY_CONFLICT")
                }
                return@transaction id
            }
            val ids = userIds(connection, kind, payload)
            db.update(
                connection,
                "INSERT INTO coordination_operation(operation_id,kind,payload,correlation_id,candidate_id,status) " +
                    "VALUES (?,?,?::jsonb,?,?,'PENDING')",
                id,
                kind,
                payload.toString(),
                correlationId,
                UUID.randomUUID(),
            )
            val participants = db.participants(connection, ids)
            participants.forEach {
                db.update(
                    connection,
                    "INSERT INTO coordination_member(operation_id,user_id) VALUES (?,?)",
                    id,
                    it.userId,
                )
            }
            if (participants.any { it.operationId != null }) {
                finish(
                    connection,
                    id,
                    OperationOutcome(
                        "REJECTED",
                        null,
                        participants.maxOf { it.version },
                        "PARTICIPATION_CONFLICT",
                    ),
                )
            } else {
                participants.forEach {
                    db.update(
                        connection,
                        "UPDATE participation SET operation_id=? WHERE user_id=?",
                        id,
                        it.userId,
                    )
                }
            }
            beforeCommit()
            id
        }

    private fun userIds(
        connection: Connection,
        kind: String,
        payload: JsonNode,
    ): List<UUID> =
        when (kind) {
            "ASSIGN_PAIR" -> payload["players"].map { UUID.fromString(it["userId"].asText()) }
            "REGISTER_ROOM" -> listOf(UUID.fromString(payload["ownerId"].asText()))
            else -> {
                val ids = mutableListOf(UUID.fromString(payload["userId"].asText()))
                if (payload.path("target").asText() == "LOBBY") {
                    owner(connection, UUID.fromString(payload["resourceId"].asText()))?.let(ids::add)
                }
                ids
            }
        }

    private fun owner(
        connection: Connection,
        roomId: UUID,
    ): UUID? =
        connection.prepareStatement("SELECT owner_id FROM room_registration WHERE room_id=?").use {
            it.setObject(1, roomId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    fun read(
        connection: Connection,
        id: UUID,
    ): CoordinationOperation? =
        connection.prepareStatement("SELECT * FROM coordination_operation WHERE operation_id=? FOR UPDATE").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                CoordinationOperation(
                    id,
                    rows.getString("kind"),
                    db.mapper.readTree(rows.getString("payload")),
                    rows.getObject("correlation_id", UUID::class.java),
                    rows.getObject("candidate_id", UUID::class.java),
                    rows.getString("status"),
                    rows.getString("result")?.let(db.mapper::readTree),
                    rows.getString("error_code"),
                )
            }
        }

    fun members(
        connection: Connection,
        id: UUID,
    ): List<UUID> {
        val query = "SELECT user_id FROM coordination_member WHERE operation_id=? ORDER BY user_id"
        return connection.prepareStatement(query).use {
            it.setObject(1, id)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
    }

    fun finish(
        connection: Connection,
        id: UUID,
        outcome: OperationOutcome,
    ): JsonNode {
        val status = outcome.status
        val resourceId = outcome.resourceId
        val version = outcome.version
        val error = outcome.error
        val result =
            db.mapper
                .createObjectNode()
                .put("operationId", id.toString())
                .put("status", status)
                .put("version", version)
        resourceId?.let { result.put("resourceId", it.toString()) }
        db.update(
            connection,
            "UPDATE coordination_operation SET status=?,result=?::jsonb,error_code=?,completed_at=clock_timestamp() " +
                "WHERE operation_id=?",
            status,
            result.toString(),
            error,
            id,
        )
        db.update(connection, "UPDATE participation SET operation_id=NULL WHERE operation_id=?", id)
        return result
    }

    fun pending(): List<UUID> =
        db.source.connection.use { connection ->
            connection.createStatement().use {
                it
                    .executeQuery(
                        "SELECT operation_id FROM coordination_operation WHERE " +
                            "status='PENDING' ORDER BY created_at LIMIT 100",
                    ).use { rows ->
                        buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }
                    }
            }
        }
}

data class OperationOutcome(
    val status: String,
    val resourceId: UUID?,
    val version: Long,
    val error: String? = null,
)
