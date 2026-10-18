package com.github.aratnitsyn.tictactoe.game.play

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class PlayPhase { X_TURN, O_TURN, PAUSED, FINISHED }

data class SymbolAssignment(
    val x: UUID,
    val o: UUID,
) {
    init {
        require(x != o)
    }

    fun mark(user: UUID): Mark =
        when (user) {
            x -> Mark.X
            o -> Mark.O
            else -> throw PlayFailure("FORBIDDEN")
        }

    fun user(mark: Mark): UUID = if (mark == Mark.X) x else o

    fun opponent(user: UUID): UUID = if (mark(user) == Mark.X) o else x

    companion object {
        fun random(players: List<UUID>): SymbolAssignment {
            require(players.size == 2)
            val assigned = players.shuffled()
            return SymbolAssignment(assigned[0], assigned[1])
        }
    }
}

data class PlayResult(
    val reason: String,
    val outcome: String,
    val winnerId: UUID? = null,
    val loserId: UUID? = null,
)

data class RecoveryState(
    val remainingNanos: Long = RECOVERY_NANOS,
    val disconnectedAt: Instant? = null,
    val deadline: Instant? = null,
) {
    fun remaining(now: Instant): Long =
        deadline?.let { Duration.between(now, it).toNanos().coerceAtLeast(0) }
            ?: remainingNanos
}

data class PlayState(
    val matchId: UUID,
    val symbol: SymbolAssignment,
    val phase: PlayPhase = PlayPhase.X_TURN,
    val board: Board = Board(),
    val version: Long = 0,
    val turnDeadline: Instant? = null,
    val savedTurn: PlayPhase = PlayPhase.X_TURN,
    val remainingTurnNanos: Long = TURN_NANOS,
    val xRecovery: RecoveryState = RecoveryState(),
    val oRecovery: RecoveryState = RecoveryState(),
    val result: PlayResult? = null,
) {
    fun recovery(user: UUID): RecoveryState = if (symbol.mark(user) == Mark.X) xRecovery else oRecovery

    fun withRecovery(
        user: UUID,
        recovery: RecoveryState,
    ): PlayState = if (symbol.mark(user) == Mark.X) copy(xRecovery = recovery) else copy(oRecovery = recovery)

    fun turnPlayer(): UUID = symbol.user(if (phase == PlayPhase.X_TURN) Mark.X else Mark.O)
}

const val TURN_NANOS = 5_000_000_000L
const val RECOVERY_NANOS = 30_000_000_000L
