package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import java.util.UUID

class LobbyFailure(
    val code: String,
    val status: Int = HttpStatus.CONFLICT.value(),
) : RuntimeException(code)

data class LobbyCommand(
    val userId: UUID,
    val commandId: UUID,
    val kind: String,
    val payload: JsonNode,
    val correlationId: UUID,
    val roomId: UUID? = null,
)

data class RoomOperation(
    val id: UUID,
    val userId: UUID,
    val kind: String,
    val roomId: UUID,
    val gamePayload: JsonNode,
    val correlationId: UUID,
    val response: JsonNode?,
    val status: Int?,
)

data class LobbyResponse(
    val status: Int,
    val body: JsonNode,
)
