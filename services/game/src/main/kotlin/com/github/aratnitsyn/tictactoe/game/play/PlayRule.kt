package com.github.aratnitsyn.tictactoe.game.play

import java.time.Duration
import java.time.Instant
import java.util.UUID

object PlayRule {
    fun start(
        id: UUID,
        symbol: SymbolAssignment,
        now: Instant,
    ): PlayState = PlayState(id, symbol, turnDeadline = now.plusNanos(TURN_NANOS))

    @Suppress("ThrowsCount") // Each rejected game rule has a distinct public error code.
    fun move(
        game: PlayState,
        user: UUID,
        cell: Int,
        now: Instant,
    ): PlayState {
        val mark = game.symbol.mark(user)
        if (game.phase !in ACTIVE) throw PlayFailure("INVALID_STATE")
        if (!now.isBefore(requireNotNull(game.turnDeadline))) throw PlayFailure("TURN_EXPIRED")
        if (game.turnPlayer() != user) throw PlayFailure("NOT_YOUR_TURN")
        val board = game.board.place(cell, mark)
        val after = game.copy(board = board)
        return when {
            board.wins(mark) -> finish(after, "WINNING_LINE", user, now)
            board.full() -> finish(after, "BOARD_FULL", null, now)
            else ->
                after.copy(
                    phase = if (mark == Mark.X) PlayPhase.O_TURN else PlayPhase.X_TURN,
                    version = game.version + 1,
                    turnDeadline = now.plusNanos(TURN_NANOS),
                )
        }
    }

    fun surrender(
        game: PlayState,
        user: UUID,
        now: Instant,
    ): PlayState {
        game.symbol.mark(user)
        if (game.phase == PlayPhase.FINISHED) throw PlayFailure("INVALID_STATE")
        return finish(game, "SURRENDER", game.symbol.opponent(user), now)
    }

    @Suppress("ReturnCount") // Exclusive turn and independent recovery deadlines are separate terminal branches.
    fun expire(
        game: PlayState,
        now: Instant,
    ): PlayState {
        if (game.phase in ACTIVE) {
            val deadline = requireNotNull(game.turnDeadline)
            return if (!now.isBefore(deadline)) {
                finish(
                    game,
                    "TURN_TIMEOUT",
                    game.symbol.opponent(game.turnPlayer()),
                    deadline,
                )
            } else {
                game
            }
        }
        if (game.phase != PlayPhase.PAUSED) return game
        val due = listOfNotNull(game.xRecovery.deadline, game.oRecovery.deadline).minOrNull() ?: return game
        if (now.isBefore(due)) return game
        if (game.xRecovery.deadline == due && game.oRecovery.deadline == due) {
            return finish(game, "BOTH_DISCONNECTED_TIMEOUT", null, due)
        }
        val loser = if (game.xRecovery.deadline == due) game.symbol.x else game.symbol.o
        return finish(game, "DISCONNECT_TIMEOUT", game.symbol.opponent(loser), due)
    }

    fun connection(
        game: PlayState,
        user: UUID,
        connected: Boolean,
        now: Instant,
    ): PlayState {
        val before = game.recovery(user)
        if (game.phase == PlayPhase.FINISHED || connected == (before.deadline == null)) return game
        val recovery =
            if (connected) {
                RecoveryState(before.remaining(now))
            } else {
                before.copy(disconnectedAt = now, deadline = now.plusNanos(before.remainingNanos))
            }
        var after = game.withRecovery(user, recovery).copy(version = game.version + 1)
        if (!connected && game.phase in ACTIVE) {
            after =
                after.copy(
                    phase = PlayPhase.PAUSED,
                    savedTurn = game.phase,
                    turnDeadline = null,
                    remainingTurnNanos = Duration.between(now, requireNotNull(game.turnDeadline)).toNanos(),
                )
        } else if (connected && after.xRecovery.deadline == null && after.oRecovery.deadline == null) {
            after = after.copy(phase = game.savedTurn, turnDeadline = now.plusNanos(game.remainingTurnNanos))
        }
        return after
    }

    private fun finish(
        game: PlayState,
        reason: String,
        winner: UUID?,
        effective: Instant,
    ): PlayState {
        val outcome =
            if (winner != null) {
                "WIN"
            } else if (reason == "BOARD_FULL") {
                "DRAW"
            } else {
                "NO_WINNER"
            }
        val loser = winner?.let(game.symbol::opponent)
        return game.copy(
            phase = PlayPhase.FINISHED,
            version = game.version + 1,
            turnDeadline = null,
            xRecovery = RecoveryState(game.xRecovery.remaining(effective)),
            oRecovery = RecoveryState(game.oRecovery.remaining(effective)),
            result = PlayResult(reason, outcome, winner, loser),
        )
    }
}

private val ACTIVE = setOf(PlayPhase.X_TURN, PlayPhase.O_TURN)
