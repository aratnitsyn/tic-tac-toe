package com.github.aratnitsyn.tictactoe.auth.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.AuthFailure
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

fun errorResponse(
    exchange: ServerWebExchange,
    mapper: ObjectMapper,
    status: HttpStatus,
    code: String,
): Mono<Void> {
    exchange.response.statusCode = status
    exchange.response.headers.contentType = MediaType.APPLICATION_JSON
    val body =
        mapper
            .createObjectNode()
            .put("code", code)
            .put("message", code)
            .put("serverTime", Instant.now().toString())
            .put("correlationId", UUID.randomUUID().toString())
    val bytes = mapper.writeValueAsBytes(body)
    return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
}

@Component
@Order(ERROR_HANDLER_ORDER)
class HttpErrors(
    private val mapper: ObjectMapper,
) : WebExceptionHandler {
    override fun handle(
        exchange: ServerWebExchange,
        ex: Throwable,
    ): Mono<Void> {
        if (exchange.response.isCommitted) return Mono.error(ex)
        val invalid =
            ex is JsonProcessingException || ex is DataBufferLimitException || ex is ServerWebInputException
        val status =
            when {
                ex is AuthFailure -> HttpStatus.valueOf(ex.status)
                invalid -> HttpStatus.BAD_REQUEST
                else -> HttpStatus.SERVICE_UNAVAILABLE
            }
        val code =
            when {
                ex is AuthFailure -> ex.code
                invalid -> "VALIDATION_ERROR"
                else -> "SERVICE_UNAVAILABLE"
            }
        return errorResponse(exchange, mapper, status, code)
    }
}

private const val ERROR_HANDLER_ORDER = -2
