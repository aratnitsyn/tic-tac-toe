package com.github.aratnitsyn.tictactoe.game.participation

import java.util.UUID

class CoordinationFailure(
    val code: String,
    val status: Int = 409,
) : RuntimeException(code)

enum class ParticipationState { MENU, QUEUED, LOBBY, IN_MATCH }

data class ClaimParticipation(
    val operationId: UUID,
    val userId: UUID,
    val expectedVersion: Long,
    val target: ParticipationState,
    val resourceId: UUID? = null,
)

data class RegisterRoom(
    val operationId: UUID,
    val roomId: UUID,
    val ownerId: UUID,
    val expectedVersion: Long,
)

data class PairPlayer(
    val userId: UUID,
    val expectedVersion: Long,
    val ticketId: UUID,
    val originalSequence: String,
)

data class AssignPair(
    val operationId: UUID,
    val matchId: UUID,
    val players: List<PairPlayer>,
)

data class Participation(
    val userId: UUID,
    val state: ParticipationState,
    val version: Long,
    val resourceId: UUID?,
    val operationId: UUID? = null,
)
