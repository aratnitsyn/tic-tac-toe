package com.github.aratnitsyn.tictactoe.lobby.room

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.UUID

interface GameClient {
    fun resolve(operation: RoomOperation): JsonNode

    fun participation(userId: UUID): JsonNode
}

class HttpGameClient(
    private val client: WebClient,
) : GameClient {
    override fun resolve(operation: RoomOperation): JsonNode {
        val known =
            client
                .get()
                .uri("/internal/v1/operations/{id}", operation.id)
                .exchangeToMono { response ->
                    when {
                        response.statusCode().value() == HttpStatus.NOT_FOUND.value() ->
                            response.releaseBody().then(
                                reactor.core.publisher.Mono
                                    .empty(),
                            )
                        response.statusCode().is2xxSuccessful -> response.bodyToMono(JsonNode::class.java)
                        else ->
                            response.createException().flatMap {
                                reactor.core.publisher.Mono
                                    .error<JsonNode>(it)
                            }
                    }
                }.block(TIMEOUT)
        if (known != null) return known
        val path = if (operation.kind == "CREATE") "/internal/v1/rooms/register" else "/internal/v1/participation/claim"
        // A lost response is unknown, never a reason to compensate or release participation.
        client
            .post()
            .uri(path)
            .header("Idempotency-Key", operation.id.toString())
            .header("X-Correlation-Id", operation.correlationId.toString())
            .bodyValue(operation.gamePayload)
            .exchangeToMono { response ->
                val accepted =
                    response.statusCode().is2xxSuccessful ||
                        response.statusCode().value() == HttpStatus.CONFLICT.value()
                if (accepted) {
                    response.releaseBody()
                } else {
                    response.createException().flatMap {
                        reactor.core.publisher.Mono
                            .error<Void>(it)
                    }
                }
            }.block(TIMEOUT)
        return client
            .get()
            .uri("/internal/v1/operations/{id}", operation.id)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block(TIMEOUT) ?: throw LobbyFailure("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value())
    }

    override fun participation(userId: UUID): JsonNode =
        client
            .get()
            .uri("/api/v1/participation")
            .header("X-User-Id", userId.toString())
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block(TIMEOUT) ?: throw LobbyFailure("SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value())
}

private val TIMEOUT = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)

private const val REQUEST_TIMEOUT_SECONDS = 5L
