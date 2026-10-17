package com.github.aratnitsyn.tictactoe.auth.security

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.AuthFailure
import com.github.aratnitsyn.tictactoe.auth.util.errorResponse
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono

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
