package com.github.aratnitsyn.tictactoe.game.logout

import com.github.aratnitsyn.tictactoe.game.participation.ClaimParticipation
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationState
import com.github.aratnitsyn.tictactoe.game.persistence.IdempotencyConflict
import com.github.aratnitsyn.tictactoe.game.play.PlayTestFixture
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationReturn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.Executors

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class LogoutTest {
    private fun service(f: PlayTestFixture) =
        LogoutService(
            f.matches,
            f.preparation,
            f.engine,
            PreparationReturn(f.db, f.event),
            f.event,
        )

    private fun payload(
        f: PlayTestFixture,
        user: UUID,
        id: UUID,
        session: UUID = UUID.randomUUID(),
    ) = f.mapper
        .createObjectNode()
        .put("operationId", id.toString())
        .put("userId", user.toString())
        .put("sessionId", session.toString())

    @Test
    fun `active and paused logout surrender once and replay cannot release a new participation`() {
        for (paused in listOf(false, true)) {
            PlayTestFixture().use { f ->
                if (paused) f.observe(f.a, false)
                val id = UUID.randomUUID()
                val body = payload(f, f.a, id)
                val first = service(f).execute(id, body)
                assertEquals("APPLIED", first["status"].asText())
                assertEquals("SURRENDER", f.game().result?.reason)
                assertEquals(f.a, f.game().result?.loserId)
                assertEquals("MENU", f.state(f.a)["state"].asText())
                assertEquals("MENU", f.state(f.b)["state"].asText())
                val ticket = UUID.randomUUID()
                val claim =
                    ClaimParticipation(
                        UUID.randomUUID(),
                        f.a,
                        f.state(f.a)["version"].asLong(),
                        ParticipationState.QUEUED,
                        ticket,
                    )
                f.participation.execute("CLAIM", f.mapper.valueToTree(claim), UUID.randomUUID())
                assertEquals(f.mapper.readTree(first.toString()), service(f).execute(id, body))
                assertEquals(ticket.toString(), f.state(f.a)["resourceId"].asText())
                assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
                assertThrows(IdempotencyConflict::class.java) { service(f).execute(id, payload(f, f.a, id)) }
            }
        }
    }

    @Test
    fun `readiness and countdown logout cancel without defeat and preserve opponent queue priority`() {
        for (countdown in listOf(false, true)) {
            PlayTestFixture(false).use { f ->
                if (countdown) {
                    f.preparation.ready(f.a, f.id, UUID.randomUUID(), f.body(0))
                    f.preparation.ready(f.b, f.id, UUID.randomUUID(), f.body(1))
                }
                val id = UUID.randomUUID()
                service(f).execute(id, payload(f, f.a, id))
                assertEquals("MENU", f.state(f.a)["state"].asText())
                assertEquals("QUEUED", f.state(f.b)["state"].asText())
                assertEquals(0, f.count("SELECT count(*) FROM game"))
                assertEquals(1, f.count("SELECT count(*) FROM game_match WHERE state='CANCELLED'"))
                assertEquals(
                    1,
                    f.count(
                        "SELECT count(*) FROM outbox WHERE event_type='ReturnToQueue' " +
                            "AND envelope->'payload'->>'originalSequence'='2'",
                    ),
                )
            }
        }
    }

    @Test
    fun `menu logout advances version and pending coordination is retried without acknowledging cleanup`() {
        PlayTestFixture().use { f ->
            val user = UUID.randomUUID()
            val id = UUID.randomUUID()
            service(f).execute(id, payload(f, user, id))
            assertEquals(1, f.state(user)["version"].asInt())
            val old = ClaimParticipation(UUID.randomUUID(), user, 0, ParticipationState.QUEUED, UUID.randomUUID())
            assertThrows(CoordinationFailure::class.java) {
                f.participation.execute("CLAIM", f.mapper.valueToTree(old), UUID.randomUUID())
            }
            val claim = old.copy(operationId = UUID.randomUUID(), expectedVersion = 1)
            val pending = f.participation.begin("CLAIM", f.mapper.valueToTree(claim), UUID.randomUUID())
            val next = UUID.randomUUID()
            val body = payload(f, user, next)
            assertEquals(503, assertThrows(CoordinationFailure::class.java) { service(f).execute(next, body) }.status)
            f.participation.apply(pending)
            service(f).execute(next, body)
            assertEquals("MENU", f.state(user)["state"].asText())
        }
    }

    @Test
    fun `logout after deadline or terminal surrender preserves existing result`() {
        for (finished in listOf(false, true)) {
            PlayTestFixture().use { f ->
                if (finished) f.surrender(f.b) else f.now = requireNotNull(f.game().turnDeadline)
                val original = if (finished) f.game() else null
                val id = UUID.randomUUID()
                service(f).execute(id, payload(f, f.a, id))
                if (finished) {
                    assertEquals(original, f.game())
                } else {
                    assertEquals("TURN_TIMEOUT", f.game().result?.reason)
                }
                assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
            }
        }
    }

    @Test
    fun `concurrent logout of both players commits one outcome and clears both`() {
        PlayTestFixture().use { f ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    listOf(f.a, f.b)
                        .map { user ->
                            executor.submit<String> {
                                val id = UUID.randomUUID()
                                service(f).execute(id, payload(f, user, id))["status"].asText()
                            }
                        }.map { it.get() }
                assertEquals(listOf("APPLIED", "APPLIED"), results)
                assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
                assertEquals("MENU", f.state(f.a)["state"].asText())
                assertEquals("MENU", f.state(f.b)["state"].asText())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `outbox failure rolls back surrender participation and logout result together`() {
        PlayTestFixture().use { f ->
            f.effect { c ->
                c.createStatement().use {
                    it.execute(
                        "CREATE FUNCTION fail_logout() RETURNS trigger LANGUAGE plpgsql AS \$\$ " +
                            "BEGIN IF NEW.event_type='GameFinished' THEN RAISE EXCEPTION 'injected'; " +
                            "END IF; RETURN NEW; END \$\$",
                    )
                    it.execute(
                        "CREATE TRIGGER fail_logout BEFORE INSERT ON outbox " +
                            "FOR EACH ROW EXECUTE FUNCTION fail_logout()",
                    )
                }
            }
            val id = UUID.randomUUID()
            val body = payload(f, f.a, id)
            val original = f.game()
            assertThrows(java.sql.SQLException::class.java) { service(f).execute(id, body) }
            assertEquals(original, f.game())
            assertEquals(2, f.count("SELECT count(*) FROM participation WHERE state='IN_MATCH'"))
            assertEquals(0, f.count("SELECT count(*) FROM command_results WHERE operation_type='LOGOUT'"))
            f.effect { c -> c.createStatement().use { it.execute("DROP TRIGGER fail_logout ON outbox") } }
            service(f).execute(id, body)
            assertEquals("SURRENDER", f.game().result?.reason)
        }
    }

    @Test
    fun `winning move racing logout has one immutable final outcome`() {
        PlayTestFixture().use { f ->
            listOf(0, 3, 1, 4).forEach { assertEquals(200, f.move(it).status) }
            val winner = f.game().symbol.x
            val version = f.game().version
            val executor = Executors.newFixedThreadPool(2)
            try {
                val move = executor.submit<Int> { f.move(2, winner, version).status }
                val logout =
                    executor.submit<String> {
                        val id = UUID.randomUUID()
                        service(f).execute(id, payload(f, winner, id))["status"].asText()
                    }
                val status = move.get()
                assertEquals("APPLIED", logout.get())
                val expected = if (status == 200) "WINNING_LINE" else "SURRENDER"
                assertEquals(expected, f.game().result?.reason)
                assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
                assertEquals("MENU", f.state(f.a)["state"].asText())
                assertEquals("MENU", f.state(f.b)["state"].asText())
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
