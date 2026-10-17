package com.github.aratnitsyn.tictactoe.auth.util

import com.github.aratnitsyn.tictactoe.auth.account.AuthFailure
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Suppress("MagicNumber") // HTTP unauthorized status.
fun token(exchange: ServerWebExchange): String =
    exchange.request.headers
        .getFirst("Authorization")
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?: exchange.request.cookies
            .getFirst("SESSION")
            ?.value ?: throw AuthFailure("UNAUTHENTICATED", 401)

// JDBC and Argon2 must never execute on the WebFlux event loop.
fun <T : Any> blocking(effect: () -> T): Mono<T> = Mono.fromCallable(effect).subscribeOn(Schedulers.boundedElastic())
