package com.github.aratnitsyn.tictactoe.auth.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.auth.account.LogoutService
import com.github.aratnitsyn.tictactoe.auth.account.ParticipationLogout
import com.github.aratnitsyn.tictactoe.auth.account.SessionService
import com.github.aratnitsyn.tictactoe.auth.account.WsTickets
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import javax.sql.DataSource

@Configuration
@EnableScheduling
class AuthRuntimeConfiguration {
    @Bean
    fun wsTickets(
        redis: StringRedisTemplate,
        sessions: SessionService,
    ) = WsTickets(redis, sessions)

    @Bean
    fun logoutService(
        source: DataSource,
        participation: ParticipationLogout,
    ) = LogoutService(source, participation)

    @Bean
    fun participationLogout(
        @Value("\${auth.game-url}") url: String,
        @Value("\${auth.game-service-token}") token: String,
    ): ParticipationLogout {
        val client =
            WebClient
                .builder()
                .baseUrl(url)
                .defaultHeader("X-Service-Token", token)
                .build()
        return ParticipationLogout { operation ->
            val result =
                client
                    .post()
                    .uri("/internal/v1/participation/logout")
                    .header("Idempotency-Key", operation.operationId.toString())
                    .bodyValue(
                        mapOf(
                            "operationId" to operation.operationId,
                            "userId" to operation.userId,
                            "sessionId" to operation.sessionId,
                        ),
                    ).retrieve()
                    .bodyToMono(JsonNode::class.java)
                    .block(Duration.ofSeconds(GAME_TIMEOUT_SECONDS))
            val sameOperation = result?.path("operationId")?.asText() == operation.operationId.toString()
            sameOperation && result?.path("status")?.asText() == "APPLIED"
        }
    }
}

private const val GAME_TIMEOUT_SECONDS = 3L
