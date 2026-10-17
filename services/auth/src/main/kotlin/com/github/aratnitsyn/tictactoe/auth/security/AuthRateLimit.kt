package com.github.aratnitsyn.tictactoe.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.tokenHash
import com.github.aratnitsyn.tictactoe.auth.util.blocking
import com.github.aratnitsyn.tictactoe.auth.util.errorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Locale

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + RATE_LIMIT_ORDER)
class AuthRateLimit(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val login = exchange.request.path.value() in listOf("/api/v1/auth/login", "/api/v1/auth/register")
        if (!login) return chain.filter(exchange)
        return DataBufferUtils
            .join(exchange.request.body, MAX_BODY_BYTES)
            .defaultIfEmpty(exchange.response.bufferFactory().wrap(ByteArray(0)))
            .flatMap { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                DataBufferUtils.release(buffer)
                blocking {
                    val document =
                        mapper.readTree(bytes)
                            ?: throw com.github.aratnitsyn.tictactoe.auth.account
                                .AuthFailure("VALIDATION_ERROR")
                    val email =
                        document
                            .path("email")
                            .asText()
                            .trim()
                            .lowercase(Locale.ROOT)
                    val source =
                        exchange.request.headers.getFirst("X-Client-IP") ?: exchange.request.remoteAddress
                            ?.address
                            ?.hostAddress ?: "unknown"
                    val ipAllowed = limit("auth-rate:source:${tokenHash(source)}", SOURCE_LIMIT)
                    val accountAllowed = limit("auth-rate:account:${tokenHash(email)}", ACCOUNT_LIMIT)
                    ipAllowed && accountAllowed
                }.flatMap { allowed ->
                    if (!allowed) {
                        exchange.response.headers.set("Retry-After", "60")
                        errorResponse(exchange, mapper, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
                    } else {
                        val request =
                            object : ServerHttpRequestDecorator(exchange.request) {
                                override fun getBody() =
                                    Flux.defer {
                                        Mono.just(exchange.response.bufferFactory().wrap(bytes))
                                    }
                            }
                        chain.filter(exchange.mutate().request(request).build())
                    }
                }
            }
    }

    private fun limit(
        key: String,
        maximum: Long,
    ) = (redis.execute(LIMIT, listOf(key)) ?: Long.MAX_VALUE) <= maximum

    companion object {
        private const val MAX_BODY_BYTES = 8192
        private const val SOURCE_LIMIT = 60L
        private const val ACCOUNT_LIMIT = 10L
        private val LIMIT =
            DefaultRedisScript(
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",
                Long::class.java,
            )
    }
}

private const val RATE_LIMIT_ORDER = 10
