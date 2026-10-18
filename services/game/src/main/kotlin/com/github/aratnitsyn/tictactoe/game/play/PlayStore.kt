package com.github.aratnitsyn.tictactoe.game.play

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class PlayStore(
    val db: CoordinationDatabase,
) {
    fun load(
        connection: Connection,
        id: UUID,
    ): PlayState? =
        connection
            .prepareStatement(
                "SELECT * FROM game WHERE match_id=? FOR UPDATE",
            ).use {
                it.setObject(1, id)
                it.executeQuery().use { row -> if (row.next()) read(row) else null }
            }

    private fun read(row: ResultSet): PlayState =
        PlayState(
            row.getObject("match_id", UUID::class.java),
            SymbolAssignment(
                row.getObject("x_player_id", UUID::class.java),
                row.getObject("o_player_id", UUID::class.java),
            ),
            PlayPhase.valueOf(row.getString("state")),
            Board(db.mapper.readTree(row.getString("board")).map { Mark.valueOf(it.asText()) }),
            row.getLong("version"),
            row.getTimestamp("turn_deadline")?.toInstant(),
            PlayPhase.valueOf(row.getString("saved_turn")),
            row.getLong("remaining_turn_ns"),
            recovery(row, "x"),
            recovery(row, "o"),
            row.getString("result")?.let { db.mapper.readValue(it, PlayResult::class.java) },
        )

    private fun recovery(
        row: ResultSet,
        mark: String,
    ) = RecoveryState(
        row.getLong("recovery_${mark}_ns"),
        row.getTimestamp("${mark}_disconnected_at")?.toInstant(),
        row.getTimestamp("${mark}_recovery_deadline")?.toInstant(),
    )

    fun save(
        connection: Connection,
        value: PlayState,
    ) {
        check(
            db.update(
                connection,
                "UPDATE game SET state=?,board=?::jsonb,version=?,turn_deadline=?,saved_turn=?,remaining_turn_ns=?," +
                    "recovery_x_ns=?,recovery_o_ns=?,x_disconnected_at=?,o_disconnected_at=?," +
                    "x_recovery_deadline=?,o_recovery_deadline=?,result=?::jsonb," +
                    "deadline_generation=deadline_generation+1," +
                    "recovery_x_ms=?,recovery_o_ms=? WHERE match_id=?",
                value.phase.name,
                db.mapper.writeValueAsString(value.board.cell),
                value.version,
                value.turnDeadline?.let(Timestamp::from),
                value.savedTurn.name,
                value.remainingTurnNanos,
                value.xRecovery.remainingNanos,
                value.oRecovery.remainingNanos,
                value.xRecovery.disconnectedAt?.let(Timestamp::from),
                value.oRecovery.disconnectedAt?.let(Timestamp::from),
                value.xRecovery.deadline?.let(Timestamp::from),
                value.oRecovery.deadline?.let(Timestamp::from),
                value.result?.let { result(it).toString() },
                value.xRecovery.remainingNanos / NANOS_PER_MS,
                value.oRecovery.remainingNanos / NANOS_PER_MS,
                value.matchId,
            ) == 1,
        )
    }

    @Suppress("NestedBlockDepth") // JSON recovery fields are optional and nested under the game snapshot.
    fun snapshot(
        value: PlayState,
        now: Instant,
    ): JsonNode =
        db.mapper.createObjectNode().apply {
            put("matchId", value.matchId.toString())
            put("state", value.phase.name)
            put("version", value.version)
            put("xPlayerId", value.symbol.x.toString())
            put("oPlayerId", value.symbol.o.toString())
            set<JsonNode>("board", db.mapper.valueToTree(value.board.cell))
            value.turnDeadline?.let { put("turnDeadline", it.toString()) }
            if (value.phase == PlayPhase.PAUSED) put("remainingTurnMs", value.remainingTurnNanos / NANOS_PER_MS)
            putObject("recoveryRemainingMs")
                .put("X", value.xRecovery.remaining(now) / NANOS_PER_MS)
                .put("O", value.oRecovery.remaining(now) / NANOS_PER_MS)
            putArray("disconnectedPlayers").also { array ->
                if (value.xRecovery.deadline != null) array.add(value.symbol.x.toString())
                if (value.oRecovery.deadline != null) array.add(value.symbol.o.toString())
            }
            if (value.xRecovery.deadline != null || value.oRecovery.deadline != null) {
                putObject("recoveryDeadlines").apply {
                    value.xRecovery.deadline?.let { put(value.symbol.x.toString(), it.toString()) }
                    value.oRecovery.deadline?.let { put(value.symbol.o.toString(), it.toString()) }
                }
            }
            value.result?.let { set<JsonNode>("result", result(it)) }
        }

    private fun result(value: PlayResult): JsonNode =
        db.mapper.createObjectNode().apply {
            put("reason", value.reason)
            put("outcome", value.outcome)
            value.winnerId?.let { put("winnerId", it.toString()) }
            value.loserId?.let { put("loserId", it.toString()) }
        }
}

private const val NANOS_PER_MS = 1_000_000L
