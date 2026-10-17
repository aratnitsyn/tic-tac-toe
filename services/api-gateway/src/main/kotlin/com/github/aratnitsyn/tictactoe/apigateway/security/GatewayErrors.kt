package com.github.aratnitsyn.tictactoe.apigateway.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

@Component
class GatewayErrors(
    private val mapper: ObjectMapper,
) {
    fun write(
        exchange: ServerWebExchange,
        status: HttpStatus,
        code: String,
    ): Mono<Void> {
        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body =
            mapOf(
                "code" to code,
                "message" to code,
                "serverTime" to Instant.now().toString(),
                "correlationId" to UUID.randomUUID().toString(),
            )
        val bytes = mapper.writeValueAsBytes(body)
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }
}
