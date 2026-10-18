package com.github.aratnitsyn.tictactoe.game.play

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class PlayDeadlineTest {
    private val f = PlayTestFixture()

    @AfterEach fun close() = f.close()

    @Test fun `old turn generation cannot end a later turn or replace its deadline`() {
        val old = f.task()
        f.move(0)
        val current = f.task()
        assertTrue(current.generation > old.generation)
        f.now = old.due.plusSeconds(20)
        f.service.expire(old)
        assertEquals(PlayPhase.O_TURN, f.game().phase)
        assertEquals(current, f.task())
        f.service.expire(current)
        assertEquals("TURN_TIMEOUT", f.game().result?.reason)
    }

    @Test fun `claimed timer survives a crashed worker and concurrent retries finish once`() {
        // Move the persisted server deadline into the past without wall-clock sleeps.
        f.effect { c ->
            f.db.update(c, "UPDATE game_deadline SET due_at=clock_timestamp()-interval '1 second'")
            f.db.update(c, "UPDATE game SET turn_deadline=(SELECT due_at FROM game_deadline WHERE kind='TURN')")
        }
        f.now = f.db.transaction(f.db::now)
        val first =
            f.engine.deadlines
                .claim()
                .single()
        assertTrue(
            f.engine.deadlines
                .claim()
                .isEmpty(),
        )
        f.effect { f.db.update(it, "UPDATE game_deadline SET lease_until=clock_timestamp()-interval '1 second'") }
        val retry =
            f.engine.deadlines
                .claim()
                .single()
        assertEquals(first, retry)
        val fresh =
            PlayService(
                f.preparation,
                f.matches,
                PlayEngine(f.db, PlayStore(f.db), f.event, PlayDeadlineStore(f.db)),
            )
        val pool = Executors.newFixedThreadPool(3)
        try {
            pool
                .invokeAll(
                    listOf(
                        Callable { f.service.expire(first) },
                        Callable { fresh.expire(retry) },
                        Callable { fresh.expire(retry) },
                    ),
                ).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("TURN_TIMEOUT", f.game().result?.reason)
        assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
        assertFalse(
            f.engine.deadlines
                .claim()
                .isNotEmpty(),
        )
    }
}
