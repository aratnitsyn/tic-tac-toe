package com.github.aratnitsyn.tictactoe.game.participation

import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class ParticipationService(
    private val db: CoordinationDatabase,
    private val operations: OperationStore,
    private val transitions: ParticipationTransition,
    private val assignments: PairAssignment,
    private val afterEffect: () -> Unit = {},
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun snapshot(userId: UUID): JsonNode =
        db.transaction { connection ->
            val value = db.participants(connection, listOf(userId)).single()
            db.mapper.createObjectNode().apply {
                put("serverTime", db.now(connection).toString())
                put("version", value.version)
                set<JsonNode>("data", db.participationJson(value))
            }
        }

    fun begin(
        kind: String,
        payload: JsonNode,
        correlationId: UUID,
    ): UUID {
        validate(kind, payload)
        return operations.begin(kind, payload, correlationId)
    }

    fun execute(
        kind: String,
        payload: JsonNode,
        correlationId: UUID,
    ): JsonNode {
        val id = begin(kind, payload, correlationId)
        apply(id)
        return result(id, true)
    }

    fun result(
        id: UUID,
        rejectAsError: Boolean = false,
    ): JsonNode =
        db.transaction { connection ->
            val op =
                operations.read(
                    connection,
                    id,
                ) ?: throw CoordinationFailure(
                    "NOT_FOUND",
                    org.springframework.http.HttpStatus.NOT_FOUND
                        .value(),
                )
            if (rejectAsError && op.errorCode != null) throw CoordinationFailure(op.errorCode)
            op.result?.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()?.apply {
                op.errorCode?.let { put("errorCode", it) }
            } ?: db.mapper
                .createObjectNode()
                .put("operationId", id.toString())
                .put("status", "PENDING")
                .put("version", 0)
        }

    fun apply(id: UUID) {
        val completed =
            db.transaction { connection ->
                db.lock(connection, "operation/$id")
                val op =
                    operations.read(connection, id)
                        ?: throw CoordinationFailure(
                            "NOT_FOUND",
                            org.springframework.http.HttpStatus.NOT_FOUND
                                .value(),
                        )
                if (op.status != "PENDING") return@transaction op
                val members = db.participants(connection, operations.members(connection, id))
                check(members.all { it.operationId == id })
                val savepoint = connection.setSavepoint()
                val outcome =
                    try {
                        val (resource, version) =
                            when (op.kind) {
                                "REGISTER_ROOM" -> register(connection, op, members.single())
                                "ASSIGN_PAIR" -> assignments.queue(connection, op, members)
                                else -> claim(connection, op, members)
                            }
                        afterEffect()
                        OperationOutcome("APPLIED", resource, version)
                    } catch (failure: CoordinationFailure) {
                        connection.rollback(savepoint)
                        OperationOutcome("REJECTED", null, members.maxOf { it.version }, failure.code)
                    } catch (failure: SQLException) {
                        if (failure.sqlState != "23505") throw failure
                        connection.rollback(savepoint)
                        OperationOutcome("REJECTED", null, members.maxOf { it.version }, "PARTICIPATION_CONFLICT")
                    }
                val result = operations.finish(connection, id, outcome)
                op.copy(status = outcome.status, result = result, errorCode = outcome.error)
            }
        logger.info(
            "Coordination committed operationId={} correlationId={} kind={} status={}",
            completed.id,
            completed.correlationId,
            completed.kind,
            completed.status,
        )
    }

    private fun register(
        connection: Connection,
        op: CoordinationOperation,
        member: Participation,
    ): Pair<UUID, Long> {
        val request = db.mapper.treeToValue(op.payload, RegisterRoom::class.java)
        transitions.expect(member, ParticipationState.MENU, request.expectedVersion)
        db.lock(connection, "room/${request.roomId}")
        db.update(
            connection,
            "INSERT INTO room_registration(room_id,owner_id,operation_id) VALUES (?,?,?)",
            request.roomId,
            member.userId,
            op.id,
        )
        db.update(
            connection,
            "INSERT INTO room_slot(room_id,slot,user_id) VALUES (?,1,?)",
            request.roomId,
            member.userId,
        )
        val after = transitions.change(connection, op, member, ParticipationState.LOBBY, request.roomId)
        return request.roomId to after.version
    }

    private fun claim(
        connection: Connection,
        op: CoordinationOperation,
        members: List<Participation>,
    ): Pair<UUID?, Long> {
        val request = db.mapper.treeToValue(op.payload, ClaimParticipation::class.java)
        val member = members.single { it.userId == request.userId }
        return when (request.target) {
            ParticipationState.QUEUED -> {
                transitions.expect(member, ParticipationState.MENU, request.expectedVersion)
                val after = transitions.change(connection, op, member, ParticipationState.QUEUED, request.resourceId)
                after.resourceId to after.version
            }
            ParticipationState.LOBBY ->
                assignments.lobby(connection, op, request, members)
            else -> {
                if (request.resourceId != null && member.resourceId != request.resourceId) {
                    throw CoordinationFailure("PARTICIPATION_CONFLICT")
                }
                null to transitions.leave(connection, op, member, request.expectedVersion).version
            }
        }
    }

    private fun validate(
        kind: String,
        payload: JsonNode,
    ) {
        validateVersions(kind, payload)
        when (kind) {
            "CLAIM" -> {
                val request = db.mapper.treeToValue(payload, ClaimParticipation::class.java)
                if (request.expectedVersion < 0 || request.target == ParticipationState.IN_MATCH) invalid()
                if (request.target != ParticipationState.MENU && request.resourceId == null) invalid()
            }
            "REGISTER_ROOM" -> {
                val request = db.mapper.treeToValue(payload, RegisterRoom::class.java)
                if (request.expectedVersion < 0) invalid()
            }
            "ASSIGN_PAIR" -> {
                val request = db.mapper.treeToValue(payload, AssignPair::class.java)
                val uniqueUsers =
                    request.players
                        .map { it.userId }
                        .distinct()
                        .size
                if (request.players.size != 2 || uniqueUsers != 2) invalid()
                if (request.players.any { it.expectedVersion < 0 }) invalid()
                if (request.players.any { !it.originalSequence.matches(Regex("^[0-9]+$")) }) invalid()
            }
            else -> invalid()
        }
    }

    private fun validateVersions(
        kind: String,
        payload: JsonNode,
    ) {
        val values =
            if (kind == "ASSIGN_PAIR") {
                if (!payload.path("players").isArray) invalid()
                payload["players"].toList()
            } else {
                listOf(payload)
            }
        values.forEach { value ->
            val version = value.path("expectedVersion")
            if (!version.isIntegralNumber || !version.canConvertToLong()) invalid()
            if (kind == "ASSIGN_PAIR" && !value.path("originalSequence").isTextual) invalid()
        }
    }

    private fun invalid(): Nothing =
        throw CoordinationFailure(
            "VALIDATION_ERROR",
            org.springframework.http.HttpStatus.BAD_REQUEST
                .value(),
        )
}
