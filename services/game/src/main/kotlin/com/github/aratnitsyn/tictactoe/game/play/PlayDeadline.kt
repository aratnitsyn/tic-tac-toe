package com.github.aratnitsyn.tictactoe.game.play

import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class PlayDeadline(
    val matchId: UUID,
    val kind: String,
    val generation: Long,
    val version: Long,
    val due: Instant,
)

class PlayDeadlineStore(
    private val db: CoordinationDatabase,
) {
    fun replace(
        connection: Connection,
        game: PlayState,
    ) {
        db.update(connection, "DELETE FROM game_deadline WHERE match_id=?", game.matchId)
        val pending =
            when (game.phase) {
                PlayPhase.X_TURN, PlayPhase.O_TURN -> listOf("TURN" to requireNotNull(game.turnDeadline))
                PlayPhase.PAUSED ->
                    listOfNotNull(
                        game.xRecovery.deadline?.let { "RECOVERY_X" to it },
                        game.oRecovery.deadline?.let { "RECOVERY_O" to it },
                    )
                PlayPhase.FINISHED -> emptyList()
            }
        pending.forEach { (kind, due) ->
            db.update(
                connection,
                "INSERT INTO game_deadline(match_id,kind,generation,expected_version,due_at) " +
                    "SELECT match_id,?,deadline_generation,version,? FROM game WHERE match_id=?",
                kind,
                Timestamp.from(due),
                game.matchId,
            )
        }
    }

    // Claim only independent timer jobs. Never hold a deadline lock while waiting for participants.
    fun claim(): List<PlayDeadline> =
        db.transaction { connection ->
            connection
                .prepareStatement(
                    "WITH due AS (SELECT match_id,kind FROM game_deadline WHERE due_at<=clock_timestamp() " +
                        "AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY due_at " +
                        "FOR UPDATE SKIP LOCKED LIMIT 100) UPDATE game_deadline d SET lease_owner=?," +
                        "lease_until=clock_timestamp()+interval '5 seconds' FROM due " +
                        "WHERE d.match_id=due.match_id AND d.kind=due.kind RETURNING d.*",
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.executeQuery().use { row ->
                        buildList {
                            while (row.next()) {
                                add(
                                    PlayDeadline(
                                        row.getObject("match_id", UUID::class.java),
                                        row.getString("kind"),
                                        row.getLong("generation"),
                                        row.getLong("expected_version"),
                                        row.getTimestamp("due_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    @Suppress("MagicNumber") // JDBC parameter indices match the immediately following SQL.
    fun current(
        connection: Connection,
        task: PlayDeadline,
    ): Boolean =
        connection
            .prepareStatement(
                "SELECT 1 FROM game_deadline d JOIN game g USING(match_id) WHERE d.match_id=? AND d.kind=? " +
                    "AND d.generation=? AND d.expected_version=? AND g.version=d.expected_version " +
                    "AND g.deadline_generation=d.generation AND d.due_at=?",
            ).use {
                it.setObject(1, task.matchId)
                it.setString(2, task.kind)
                it.setLong(3, task.generation)
                it.setLong(4, task.version)
                it.setTimestamp(5, Timestamp.from(task.due))
                it.executeQuery().use { row -> row.next() }
            }
}
