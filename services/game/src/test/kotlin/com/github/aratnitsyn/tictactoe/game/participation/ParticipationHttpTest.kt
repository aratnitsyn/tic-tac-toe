package com.github.aratnitsyn.tictactoe.game.participation

import com.github.aratnitsyn.tictactoe.game.persistence.GameTestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParticipationHttpTest {
    @LocalServerPort
    private var port: Int = 0
    private val client get() = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$port").build()

    @Test
    fun `credential gates snapshots and coordination before accepting a supplied identity`() {
        client
            .get()
            .uri("/api/v1/participation")
            .header("X-User-Id", UUID.randomUUID().toString())
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/internal/v1/operations/${UUID.randomUUID()}")
            .header("X-Service-Token", "wrong")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    private fun post(
        request: ClaimParticipation,
        key: UUID = request.operationId,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/internal/v1/participation/claim")
            .header("X-Service-Token", TOKEN)
            .header("Idempotency-Key", key.toString())
            .bodyValue(request)
            .exchange()

    @Test
    fun `HTTP coordination persists result snapshot version and stable replay`() {
        val request =
            ClaimParticipation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                ParticipationState.QUEUED,
                UUID.randomUUID(),
            )
        post(request)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("APPLIED")
            .jsonPath("$.version")
            .isEqualTo(1)
        client
            .get()
            .uri("/api/v1/participation")
            .header("X-Service-Token", TOKEN)
            .header("X-User-Id", request.userId.toString())
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.data.state")
            .isEqualTo("QUEUED")
            .jsonPath("$.version")
            .isEqualTo(1)
        client
            .get()
            .uri("/internal/v1/operations/${request.operationId}")
            .header("X-Service-Token", TOKEN)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("APPLIED")
        post(request).expectStatus().isOk
        post(request, UUID.randomUUID())
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("IDEMPOTENCY_CONFLICT")
    }

    @Test
    fun `invalid input and absent operations return explicit errors without reservation`() {
        val user = UUID.randomUUID()
        val id = UUID.randomUUID()
        val body = mapOf("operationId" to id, "userId" to user, "expectedVersion" to 0, "target" to "QUEUED")
        client
            .post()
            .uri("/internal/v1/participation/claim")
            .header("X-Service-Token", TOKEN)
            .header("Idempotency-Key", id.toString())
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("VALIDATION_ERROR")
        client
            .get()
            .uri("/internal/v1/operations/$id")
            .header("X-Service-Token", TOKEN)
            .exchange()
            .expectStatus()
            .isNotFound
        client
            .get()
            .uri("/api/v1/participation")
            .header("X-Service-Token", TOKEN)
            .header("X-User-Id", user.toString())
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.data.state")
            .isEqualTo("MENU")
    }

    @Test
    fun `missing or coerced version is rejected rather than interpreted as initial version zero`() {
        for (version in listOf(null, "0")) {
            val id = UUID.randomUUID()
            val body =
                mutableMapOf<String, Any>(
                    "operationId" to id,
                    "userId" to UUID.randomUUID(),
                    "target" to "QUEUED",
                    "resourceId" to UUID.randomUUID(),
                )
            if (version != null) body["expectedVersion"] = version
            client
                .post()
                .uri("/internal/v1/participation/claim")
                .header("X-Service-Token", TOKEN)
                .header("Idempotency-Key", id.toString())
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_ERROR")
        }
    }

    companion object {
        private val database by lazy { GameTestDatabase() }
        private val TOKEN = UUID.randomUUID().toString()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("PG_JDBC_URL") }
            registry.add("spring.datasource.username") { System.getenv("PGUSER") }
            registry.add("spring.datasource.password") { System.getenv("PGPASSWORD") }
            registry.add("spring.datasource.hikari.schema") { database.source.currentSchema }
            registry.add("game.service-token") { TOKEN }
            registry.add("events.url") { System.getenv("NATS_URL") }
            registry.add("events.user") { System.getenv("NATS_USER") }
            registry.add("events.password") { System.getenv("NATS_PASSWORD") }
            registry.add("coordination.retry-delay") { "3600000" }
        }

        @JvmStatic
        @AfterAll
        fun closeDatabase() = database.close()
    }
}
