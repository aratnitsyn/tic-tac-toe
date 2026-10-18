package com.github.aratnitsyn.tictactoe.game.play

import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparedMatch
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class PlayChange(
    val before: PlayState,
    val after: PlayState,
    val now: Instant,
    val type: String,
)

class PlayEngine(
    private val db: CoordinationDatabase,
    val store: PlayStore,
    private val events: PreparationEvent,
    val deadlines: PlayDeadlineStore,
) {
    fun start(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        now: Instant,
    ) {
        val game = PlayRule.start(match.id, SymbolAssignment.random(match.members.map { it.user }), now)
        db.update(
            connection,
            "INSERT INTO game(match_id,state,board,x_player_id,o_player_id,turn_deadline) " +
                "VALUES (?,'X_TURN',?::jsonb,?,?,?)",
            match.id,
            db.mapper.writeValueAsString(game.board.cell),
            game.symbol.x,
            game.symbol.o,
            Timestamp.from(requireNotNull(game.turnDeadline)),
        )
        store.save(connection, game)
        deadlines.replace(connection, game)
        db.update(connection, "UPDATE game_match SET state='RUNNING',version=version+1 WHERE match_id=?", match.id)
        events.emit(connection, match, cause, event(game, now, "GameStarted"))
    }

    fun advance(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        now: Instant,
    ): PlayState? {
        val before = store.load(connection, match.id) ?: return null
        return if (match.state !=
            "RUNNING"
        ) {
            before
        } else {
            commit(connection, match, cause, PlayChange(before, PlayRule.expire(before, now), now, "GameFinished"))
        }
    }

    fun connection(
        connection: Connection,
        match: PreparedMatch,
        user: UUID,
        cause: UUID,
        now: Instant,
    ) {
        val before = requireNotNull(store.load(connection, match.id))
        val connected = match.members.single { it.user == user }.connected
        val after = PlayRule.connection(before, user, connected, now)
        val type = if (after.phase == PlayPhase.PAUSED) "GamePaused" else "GameResumed"
        commit(connection, match, cause, PlayChange(before, after, now, type))
    }

    fun commit(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        change: PlayChange,
    ): PlayState {
        val before = change.before
        val after = change.after
        if (before == after) return before
        store.save(connection, after)
        deadlines.replace(connection, after)
        val eventType =
            if (after.phase == PlayPhase.FINISHED) {
                PlayFinish(db, events).apply(connection, match, cause)
                "GameFinished"
            } else {
                change.type
            }
        events.emit(connection, match, cause, event(after, change.now, eventType))
        return after
    }

    private fun event(
        game: PlayState,
        now: Instant,
        type: String,
    ) = CoordinationEvent(
        type,
        "GAME",
        game.matchId,
        game.version,
        listOf(game.symbol.x, game.symbol.o),
        store.snapshot(game, now),
    )
}
