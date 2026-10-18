package com.github.aratnitsyn.tictactoe.game.preparation

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.participation.Participation
import java.sql.Connection
import java.time.Instant
import java.util.UUID

data class MatchMember(
    val user: UUID,
    val ready: Boolean,
    val connected: Boolean,
    val generation: Long,
    val ticket: UUID?,
    val sequence: String?,
    val returnVersion: Long?,
)

data class PreparedMatch(
    val id: UUID,
    val source: String,
    val sourceRef: UUID,
    val state: String,
    val version: Long,
    val readyDeadline: Instant,
    val startDeadline: Instant?,
    val operation: UUID,
    val correlation: UUID,
    val members: List<MatchMember>,
    val participation: List<Participation>,
)

@Suppress("MagicNumber") // JDBC column positions and HTTP error statuses.
class PreparationStore(
    val db: CoordinationDatabase,
) {
    // Same lock order as coordination: sorted participants, then match. Membership is immutable.
    fun lock(
        connection: Connection,
        id: UUID,
    ): PreparedMatch {
        val users =
            connection.prepareStatement("SELECT user_id FROM match_member WHERE match_id=?").use {
                it.setObject(1, id)
                it.executeQuery().use { row ->
                    buildList {
                        while (row.next()) add(row.getObject(1, UUID::class.java))
                    }
                }
            }
        if (users.isEmpty()) throw CoordinationFailure("NOT_FOUND", 404)
        val participation = db.participants(connection, users)
        if (participation.any { it.operationId != null }) throw CoordinationFailure("SERVICE_UNAVAILABLE", 503)
        db.lock(connection, "match/$id")
        val members =
            connection.prepareStatement("SELECT * FROM match_member WHERE match_id=? ORDER BY slot").use {
                it.setObject(1, id)
                it.executeQuery().use { row ->
                    buildList {
                        while (row.next()) {
                            add(
                                MatchMember(
                                    row.getObject("user_id", UUID::class.java),
                                    row.getBoolean("ready"),
                                    row.getBoolean("connected"),
                                    row.getLong("connection_generation"),
                                    row.getObject("ticket_id", UUID::class.java),
                                    row.getString("original_sequence"),
                                    row.getObject("return_version")?.let { value -> (value as Number).toLong() },
                                ),
                            )
                        }
                    }
                }
            }
        connection.prepareStatement("SELECT match_id FROM game WHERE match_id=? FOR UPDATE").use {
            it.setObject(1, id)
            it.executeQuery().use { row -> row.next() }
        }
        return connection.prepareStatement("SELECT * FROM game_match WHERE match_id=? FOR UPDATE").use {
            it.setObject(1, id)
            it.executeQuery().use { row ->
                check(row.next())
                PreparedMatch(
                    id,
                    row.getString("source"),
                    row.getObject("source_ref", UUID::class.java),
                    row.getString("state"),
                    row.getLong("version"),
                    row.getTimestamp("ready_deadline").toInstant(),
                    row.getTimestamp("start_deadline")?.toInstant(),
                    row.getObject("operation_id", UUID::class.java),
                    row.getObject("correlation_id", UUID::class.java),
                    members,
                    participation,
                )
            }
        }
    }

    fun snapshot(value: PreparedMatch): JsonNode =
        db.mapper.createObjectNode().apply {
            put("matchId", value.id.toString())
            put("source", value.source)
            put("sourceRef", value.sourceRef.toString())
            put("state", value.state)
            put("version", value.version)
            put("readyDeadline", value.readyDeadline.toString())
            value.startDeadline?.let { put("startDeadline", it.toString()) }
            putArray("participants").also { array ->
                value.members.forEach {
                    array.addObject().put("userId", it.user.toString()).put("ready", it.ready)
                }
            }
        }
}
