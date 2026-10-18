package com.github.aratnitsyn.tictactoe.game.play

import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.persistence.IdempotencyConflict
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class PlayTest {
    private val f = PlayTestFixture()

    @AfterEach fun close() = f.close()

    @Test fun `move is atomic historical replay is stable and changed payload conflicts`() {
        val initial = f.game()
        val key = UUID.randomUUID()
        val first = f.move(0, key = key)
        assertEquals(200, first.status)
        f.now = f.now.plusMillis(300)
        val replay = f.move(0, initial.symbol.x, 0, key)
        assertEquals(f.mapper.readTree(first.body.toString()), f.mapper.readTree(replay.body.toString()))
        assertEquals(1L, f.game().version)
        assertEquals(initial.symbol, f.game().symbol)
        assertThrows(IdempotencyConflict::class.java) { f.move(1, initial.symbol.x, 0, key) }
        assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameStateChanged'"))
    }

    @Test fun `foreign turn occupied cell stale version paused and malformed moves are rejected`() {
        val initial = f.game()
        assertEquals("NOT_YOUR_TURN", f.move(0, initial.symbol.o).body["code"].asText())
        assertEquals(403, assertThrows(CoordinationFailure::class.java) { f.move(0, UUID.randomUUID()) }.status)
        assertEquals(
            403,
            assertThrows(CoordinationFailure::class.java) {
                f.service.snapshot(UUID.randomUUID(), f.id)
            }.status,
        )
        assertEquals(200, f.move(0).status)
        assertEquals("VERSION_CONFLICT", f.move(1, version = 0).body["code"].asText())
        assertEquals("CELL_OCCUPIED", f.move(0).body["code"].asText())
        val invalid =
            listOf(
                "{\"expectedVersion\":1,\"cell\":9}",
                "{\"expectedVersion\":1,\"cell\":0.5}",
                "{\"expectedVersion\":1,\"cell\":null}",
                "{\"expectedVersion\":1,\"cell\":2,\"extra\":1}",
            )
        invalid.forEach { json ->
            assertEquals(
                400,
                assertThrows(CoordinationFailure::class.java) {
                    f.service.command(f.a, f.id, UUID.randomUUID(), "MOVE", f.mapper.readTree(json))
                }.status,
            )
        }
        f.observe(initial.symbol.x, false)
        assertEquals("INVALID_STATE", f.move(1, initial.symbol.o).body["code"].asText())
        assertEquals(
            1,
            f
                .game()
                .board.cell
                .count { it != Mark.EMPTY },
        )
    }

    @Test fun `move exactly at deadline first commits timeout and releases both players`() {
        val initial = f.game()
        f.now = requireNotNull(initial.turnDeadline)
        assertEquals(409, f.move(0).status)
        val terminal = f.game()
        assertEquals("TURN_TIMEOUT", terminal.result?.reason)
        assertEquals(initial.symbol.o, terminal.result?.winnerId)
        assertEquals(Board(), terminal.board)
        assertEquals("MENU", f.state(f.a)["state"].asText())
        assertEquals("MENU", f.state(f.b)["state"].asText())
        assertEquals(0, f.count("SELECT count(*) FROM match_member WHERE active"))
        assertEquals(0, f.count("SELECT count(*) FROM game_deadline"))
        assertEquals(409, f.surrender().status)
        assertEquals(terminal, f.game())
    }

    @Test fun `winning move draw and surrender share atomic completion`() {
        listOf(0, 3, 1, 4, 2).forEach { assertEquals(200, f.move(it).status) }
        assertEquals("WINNING_LINE", f.game().result?.reason)
        assertEquals(1, f.count("SELECT count(*) FROM game_match WHERE state='FINISHED'"))
        assertEquals(2, f.count("SELECT count(*) FROM participation WHERE state='MENU'"))
        assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
    }

    @Test fun `competing winning move and surrender produce one immutable result`() {
        listOf(0, 3, 1, 4).forEach { f.move(it) }
        val game = f.game()
        val pool = Executors.newFixedThreadPool(2)
        val result =
            try {
                pool
                    .invokeAll(
                        listOf(
                            Callable { f.move(2, game.symbol.x, game.version).status },
                            Callable { f.surrender(game.symbol.x, game.version).status },
                        ),
                    ).map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        assertEquals(listOf(200, 409), result.sorted())
        assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
        val terminal = f.game()
        assertEquals(409, f.move(5, game.symbol.o).status)
        assertEquals(terminal, f.game())
    }

    @Test fun `overdue worker move and surrender all obey earliest turn deadline`() {
        val game = f.game()
        val task = f.task()
        f.now = requireNotNull(game.turnDeadline).plusSeconds(50)
        val pool = Executors.newFixedThreadPool(3)
        try {
            pool
                .invokeAll(
                    listOf(
                        Callable {
                            f.move(0)
                            true
                        },
                        Callable {
                            f.surrender()
                            true
                        },
                        Callable {
                            f.service.expire(task)
                            true
                        },
                    ),
                ).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("TURN_TIMEOUT", f.game().result?.reason)
        assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
    }

    @Test fun `failure while emitting final event rolls back result participation and command`() {
        f.effect { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE FUNCTION fail_finish() RETURNS trigger LANGUAGE plpgsql AS \$\$ " +
                        "BEGIN IF NEW.event_type='GameFinished' THEN RAISE EXCEPTION 'injected'; " +
                        "END IF; RETURN NEW; END \$\$",
                )
                it.execute(
                    "CREATE TRIGGER fail_finish BEFORE INSERT ON outbox " +
                        "FOR EACH ROW EXECUTE FUNCTION fail_finish()",
                )
            }
        }
        val version = f.game().version
        assertThrows(java.sql.SQLException::class.java) { f.surrender() }
        assertEquals(version, f.game().version)
        assertEquals(2, f.count("SELECT count(*) FROM participation WHERE state='IN_MATCH'"))
        assertEquals(0, f.count("SELECT count(*) FROM command_results WHERE operation_type LIKE 'SURRENDER/%'"))
        f.effect { it.createStatement().use { s -> s.execute("DROP TRIGGER fail_finish ON outbox") } }
        assertEquals(200, f.surrender().status)
        assertEquals("SURRENDER", f.game().result?.reason)
    }

    @Test fun `server clock is read only after waiting for the game row lock`() {
        val realPreparation =
            com.github.aratnitsyn.tictactoe.game.preparation.PreparationService(
                f.matches,
                com.github.aratnitsyn.tictactoe.game.persistence
                    .CommandStore(f.db.source, f.mapper),
                f.event,
                com.github.aratnitsyn.tictactoe.game.preparation
                    .PreparationReturn(f.db, f.event),
                f.db::now,
                f.engine,
            )
        val realService = PlayService(realPreparation, f.matches, f.engine)
        f.effect { c ->
            f.db.update(c, "UPDATE game SET turn_deadline=clock_timestamp()+interval '100 milliseconds'")
        }
        val user = f.game().symbol.x
        f.db.source.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("SELECT match_id FROM game FOR UPDATE") }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val future =
                    pool.submit(
                        Callable {
                            realService.command(user, f.id, UUID.randomUUID(), "MOVE", f.body(0, 0))
                        },
                    )
                Thread.sleep(250)
                blocker.commit()
                assertEquals(409, future.get(10, TimeUnit.SECONDS).status)
            } finally {
                blocker.rollback()
                pool.shutdownNow()
            }
        }
        assertEquals("TURN_TIMEOUT", f.game().result?.reason)
        assertEquals(Board(), f.game().board)
    }

    @Test fun `draw finishes transactionally and releases both participants`() {
        listOf(0, 1, 2, 4, 3, 5, 7, 6, 8).forEach { assertEquals(200, f.move(it).status) }
        assertEquals("DRAW", f.game().result?.outcome)
        assertEquals(2, f.count("SELECT count(*) FROM participation WHERE state='MENU'"))
        assertEquals(1, f.count("SELECT count(*) FROM outbox WHERE event_type='GameFinished'"))
    }
}
