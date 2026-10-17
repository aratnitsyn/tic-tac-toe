package com.github.aratnitsyn.tictactoe.game.participation

import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

class PairAssignment(
    private val db: CoordinationDatabase,
    private val transitions: ParticipationTransition,
    private val events: ParticipationEvent,
) {
    fun queue(
        connection: Connection,
        op: CoordinationOperation,
        members: List<Participation>,
    ): Pair<UUID, Long> {
        val request = db.mapper.treeToValue(op.payload, AssignPair::class.java)
        request.players.forEach { player ->
            val member = members.single { it.userId == player.userId }
            transitions.expect(member, ParticipationState.QUEUED, player.expectedVersion)
            if (member.resourceId != player.ticketId) throw CoordinationFailure("PARTICIPATION_CONFLICT")
        }
        return create(connection, op, members, PairSource(request.matchId, "QUEUE", op.id, op.payload["players"]))
    }

    fun lobby(
        connection: Connection,
        op: CoordinationOperation,
        request: ClaimParticipation,
        members: List<Participation>,
    ): Pair<UUID, Long> {
        val joining = members.single { it.userId == request.userId }
        val roomId = requireNotNull(request.resourceId)
        transitions.expect(joining, ParticipationState.MENU, request.expectedVersion)
        db.lock(connection, "room/$roomId")
        val owner =
            connection
                .prepareStatement(
                    "SELECT owner_id,active FROM room_registration " +
                        "WHERE room_id=? FOR UPDATE",
                ).use {
                    it.setObject(1, roomId)
                    it.executeQuery().use { rows ->
                        if (!rows.next() || !rows.getBoolean("active")) unavailable()
                        rows.getObject("owner_id", UUID::class.java)
                    }
                }
        val host = members.singleOrNull { it.userId == owner } ?: unavailable()
        if (host.userId == joining.userId || host.state != ParticipationState.LOBBY || host.resourceId != roomId) {
            unavailable()
        }
        val slots =
            connection.prepareStatement("SELECT user_id FROM room_slot WHERE room_id=? ORDER BY slot").use {
                it.setObject(1, roomId)
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                rows.getObject(
                                    1,
                                    UUID::class.java,
                                ),
                            )
                        }
                    }
                }
            }
        if (slots != listOf(owner)) unavailable()
        db.update(connection, "INSERT INTO room_slot(room_id,slot,user_id) VALUES (?,2,?)", roomId, joining.userId)
        val result = create(connection, op, listOf(host, joining), PairSource(op.candidateId, "LOBBY", roomId))
        db.update(connection, "DELETE FROM room_slot WHERE room_id=?", roomId)
        db.update(connection, "UPDATE room_registration SET active=false WHERE room_id=?", roomId)
        return result
    }

    private fun unavailable(): Nothing = throw CoordinationFailure("LOBBY_UNAVAILABLE")

    @Suppress("MagicNumber") // The agreed common ready deadline is ten seconds.
    private fun create(
        connection: Connection,
        op: CoordinationOperation,
        members: List<Participation>,
        sourceData: PairSource,
    ): Pair<UUID, Long> {
        val matchId = sourceData.matchId
        val source = sourceData.source
        val sourceRef = sourceData.sourceRef
        val tickets = sourceData.tickets
        db.lock(connection, "match/$matchId")
        val deadline = db.now(connection).plusSeconds(10)
        db.update(
            connection,
            "INSERT INTO game_match(match_id,source,source_ref,state,ready_deadline,operation_id,correlation_id) " +
                "VALUES (?,?,?,'WAITING_READY',?,?,?)",
            matchId,
            source,
            sourceRef,
            Timestamp.from(deadline),
            op.id,
            op.correlationId,
        )
        members.forEachIndexed { index, member ->
            val ticket = tickets?.firstOrNull { it["userId"].asText() == member.userId.toString() }
            db.update(
                connection,
                "INSERT INTO " +
                    "match_member(match_id,user_id,slot,ticket_id,original_sequence) " +
                    "VALUES (?,?,?,?,?::numeric)",
                matchId,
                member.userId,
                index + 1,
                ticket?.let { UUID.fromString(it["ticketId"].asText()) },
                ticket?.get("originalSequence")?.asText(),
            )
            transitions.change(connection, op, member, ParticipationState.IN_MATCH, matchId)
        }
        val payload = snapshot(sourceData, deadline, members)
        events.emit(
            connection,
            op,
            CoordinationEvent(
                "MatchReadyRequested",
                "MATCH",
                matchId,
                0,
                members.map { it.userId },
                payload,
            ),
        )
        return matchId to 0L
    }

    private fun snapshot(
        sourceData: PairSource,
        deadline: java.time.Instant,
        members: List<Participation>,
    ): JsonNode {
        val matchId = sourceData.matchId
        val source = sourceData.source
        val sourceRef = sourceData.sourceRef
        return db.mapper.createObjectNode().apply {
            put("matchId", matchId.toString())
            put("source", source)
            put("sourceRef", sourceRef.toString())
            put("state", "WAITING_READY")
            put("version", 0)
            put("readyDeadline", deadline.toString())
            putArray("participants").also { array ->
                members.forEach {
                    array.addObject().put("userId", it.userId.toString()).put("ready", false)
                }
            }
        }
    }
}

private data class PairSource(
    val matchId: UUID,
    val source: String,
    val sourceRef: UUID,
    val tickets: JsonNode? = null,
)
