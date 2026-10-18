package com.github.aratnitsyn.tictactoe.game.preparation

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import org.springframework.http.HttpStatus
import java.util.UUID

object PreparationInput {
    fun ready(payload: JsonNode): Long {
        if (!payload.isObject || payload.size() != 1) invalid()
        return version(payload.path("expectedVersion"))
    }

    fun connection(
        payload: JsonNode,
        user: UUID,
        match: UUID,
        command: UUID,
    ): Long {
        if (!payload.isObject || payload.size() != CONNECTION_FIELD_COUNT) invalid()
        if (!payload.path("connected").isBoolean) invalid()
        if (payload.path("operationId").asText() != command.toString()) invalid()
        if (payload.path("matchId").asText() != match.toString()) invalid()
        if (payload.path("userId").asText() != user.toString()) invalid()
        return version(payload.path("generation"))
    }

    private fun version(value: JsonNode): Long {
        if (!value.isIntegralNumber || !value.canConvertToLong() || value.asLong() < 0) invalid()
        return value.asLong()
    }

    private fun invalid(): Nothing = throw CoordinationFailure("VALIDATION_ERROR", HttpStatus.BAD_REQUEST.value())
}

private const val CONNECTION_FIELD_COUNT = 5
