package com.github.aratnitsyn.tictactoe.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.AuthFailure
import com.github.aratnitsyn.tictactoe.auth.util.errorResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@Component
class ServiceAuthorization(
    @param:Value("\${auth.service-token}") private val secret: String,
    private val mapper: ObjectMapper = ObjectMapper(),
) : WebFilter {
    init {
        require(secret.length >= MIN_SERVICE_TOKEN_LENGTH) { "Service credential must contain at least 32 characters" }
    }

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        exchange.response.headers.cacheControl = "no-store"
        val health =
            exchange.request.path
                .value()
                .startsWith("/actuator/")
        val supplied = exchange.request.headers.getFirst("X-Service-Token") ?: ""
        if (!health && !MessageDigest.isEqual(secret.toByteArray(), supplied.toByteArray())) {
            return errorResponse(exchange, mapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        }
        return chain.filter(exchange)
    }
}

@RestControllerAdvice
class AuthErrors(
    private val mapper: ObjectMapper,
) {
    @ExceptionHandler(AuthFailure::class)
    fun failure(error: AuthFailure): org.springframework.http.ResponseEntity<Any> =
        org.springframework.http.ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_JSON).body(
            mapper
                .createObjectNode()
                .put(
                    "code",
                    error.code,
                ).put("message", error.reason)
                .put("serverTime", Instant.now().toString())
                .put("correlationId", UUID.randomUUID().toString()),
        )
}

private const val MIN_SERVICE_TOKEN_LENGTH = 32
