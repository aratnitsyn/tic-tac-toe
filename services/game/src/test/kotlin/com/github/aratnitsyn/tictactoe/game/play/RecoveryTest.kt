package com.github.aratnitsyn.tictactoe.game.play

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class RecoveryTest {
    private val f = PlayTestFixture()

    @AfterEach fun close() = f.close()

    @Test fun `pause preserves two seconds and repeated absence spends only a shared thirty second budget`() {
        val symbol = f.game().symbol
        f.now = requireNotNull(f.game().turnDeadline).minusSeconds(2)
        val obsolete = f.task()
        f.observe(symbol.x, false)
        assertEquals(PlayPhase.PAUSED, f.game().phase)
        assertEquals(2_000_000_000, f.game().remainingTurnNanos)
        val pause = f.game()
        f.observe(symbol.x, false)
        assertEquals(pause, f.game())
        f.now = f.now.plusSeconds(12)
        f.service.expire(obsolete)
        assertEquals(PlayPhase.PAUSED, f.game().phase)
        f.observe(symbol.x, true)
        val resumed = f.game()
        assertEquals(PlayPhase.X_TURN, resumed.phase)
        assertEquals(f.now.plusSeconds(2), resumed.turnDeadline)
        assertEquals(18_000_000_000, resumed.xRecovery.remainingNanos)
        assertEquals(RECOVERY_NANOS, resumed.oRecovery.remainingNanos)
        f.now = f.now.plusMillis(500)
        f.observe(symbol.x, false)
        assertEquals(1_500_000_000, f.game().remainingTurnNanos)
        f.now = f.now.plusSeconds(18)
        f.observe(symbol.x, true)
        assertEquals("DISCONNECT_TIMEOUT", f.game().result?.reason)
        assertEquals(symbol.o, f.game().result?.winnerId)
        assertEquals(0L, f.game().xRecovery.remainingNanos)
        assertEquals(RECOVERY_NANOS, f.game().oRecovery.remainingNanos)
    }

    @Test fun `return of only one player does not resume and stops only that budget`() {
        val symbol = f.game().symbol
        f.observe(symbol.x, false)
        f.now = f.now.plusSeconds(3)
        f.observe(symbol.o, false)
        f.now = f.now.plusSeconds(9)
        f.observe(symbol.x, true)
        assertEquals(PlayPhase.PAUSED, f.game().phase)
        assertEquals(18_000_000_000, f.game().xRecovery.remainingNanos)
        val deadline = f.game().oRecovery.deadline
        f.now = f.now.plusSeconds(1)
        val snapshot = f.service.snapshot(symbol.x, f.id)["data"]
        assertEquals(18000, snapshot["recoveryRemainingMs"]["X"].asInt())
        assertEquals(20000, snapshot["recoveryRemainingMs"]["O"].asInt())
        assertEquals(deadline, f.game().oRecovery.deadline)
        f.observe(symbol.o, true)
        assertEquals(PlayPhase.X_TURN, f.game().phase)
        assertEquals(20_000_000_000, f.game().oRecovery.remainingNanos)
    }

    @Test fun `same recovery deadline produces no winner rather than board draw`() {
        val symbol = f.game().symbol
        f.observe(symbol.x, false)
        f.observe(symbol.o, false)
        assertEquals(f.game().xRecovery.deadline, f.game().oRecovery.deadline)
        f.now = f.now.plusSeconds(60)
        f.service.expire(f.task())
        assertEquals("BOTH_DISCONNECTED_TIMEOUT", f.game().result?.reason)
        assertEquals("NO_WINNER", f.game().result?.outcome)
        assertEquals(null, f.game().result?.winnerId)
        assertEquals("MENU", f.state(symbol.x)["state"].asText())
        assertEquals("MENU", f.state(symbol.o)["state"].asText())
    }

    @Test fun `late worker chooses earlier recovery deadline even if both are now overdue`() {
        val symbol = f.game().symbol
        f.observe(symbol.x, false)
        f.now = f.now.plusSeconds(4)
        f.observe(symbol.o, false)
        val later = f.task().copy(kind = "RECOVERY_O", due = requireNotNull(f.game().oRecovery.deadline))
        f.now = f.now.plusSeconds(100)
        f.service.expire(later)
        val terminal = f.game()
        assertEquals("DISCONNECT_TIMEOUT", terminal.result?.reason)
        assertEquals(symbol.x, terminal.result?.loserId)
        assertEquals(4_000_000_000, terminal.oRecovery.remainingNanos)
        f.now = f.now.plusSeconds(100)
        assertEquals(4000, f.service.snapshot(symbol.o, f.id)["data"]["recoveryRemainingMs"]["O"].asInt())
        assertEquals(terminal, f.game())
    }

    @Test fun `late disconnect cannot turn an expired turn into a fresh pause`() {
        val game = f.game()
        f.now = requireNotNull(game.turnDeadline)
        f.observe(game.symbol.x, false)
        assertEquals("TURN_TIMEOUT", f.game().result?.reason)
        assertEquals(RECOVERY_NANOS, f.game().xRecovery.remainingNanos)
    }

    @Test fun `new generation fences old close without resetting recovery or turn`() {
        val symbol = f.game().symbol
        f.observe(symbol.x, true, 2)
        val before = f.game()
        f.observe(symbol.x, false, 1)
        assertEquals(before, f.game())
        f.observe(symbol.x, false, 2)
        val paused = f.game()
        f.observe(symbol.x, true, 2)
        assertEquals(paused, f.game())
        f.now = f.now.plusSeconds(1)
        f.observe(symbol.x, true, 3)
        val resumed = f.game()
        f.observe(symbol.x, false, 2)
        assertEquals(resumed, f.game())
        assertEquals(29_000_000_000, resumed.xRecovery.remainingNanos)
    }

    @Test fun `surrender while paused is normal defeat and releases participation`() {
        val symbol = f.game().symbol
        f.observe(symbol.x, false)
        assertEquals(200, f.surrender(symbol.o).status)
        assertEquals("SURRENDER", f.game().result?.reason)
        assertEquals(symbol.o, f.game().result?.loserId)
        assertEquals(2, f.count("SELECT count(*) FROM participation WHERE state='MENU'"))
    }

    @Test fun `reconnect a microsecond before expiry restores remaining turn but equality loses`() {
        val symbol = f.game().symbol
        f.observe(symbol.x, false)
        val deadline = requireNotNull(f.game().xRecovery.deadline)
        f.now = deadline.minusNanos(1000)
        f.observe(symbol.x, true)
        assertEquals(1000L, f.game().xRecovery.remainingNanos)
        assertEquals(PlayPhase.X_TURN, f.game().phase)
        f.observe(symbol.x, false)
        f.now = requireNotNull(f.game().xRecovery.deadline)
        f.observe(symbol.x, true)
        assertEquals("DISCONNECT_TIMEOUT", f.game().result?.reason)
        assertTrue(f.game().result?.outcome == "WIN")
    }
}
