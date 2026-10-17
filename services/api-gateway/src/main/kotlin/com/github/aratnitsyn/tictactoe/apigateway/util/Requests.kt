package com.github.aratnitsyn.tictactoe.apigateway.util

fun nativeRequest(exchange: org.springframework.web.server.ServerWebExchange): Boolean {
    val noOrigin = exchange.request.headers.origin == null
    val noCookie = exchange.request.cookies.getFirst("SESSION") == null
    val credential =
        exchange.request.headers
            .getFirst("Authorization")
            ?.startsWith("Bearer ") == true
    val declared = exchange.request.headers.getFirst("X-Client-Type") == "NATIVE"
    return noOrigin && noCookie && (credential || declared)
}
