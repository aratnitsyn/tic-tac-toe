package com.github.aratnitsyn.tictactoe.game.play

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PlayRuleTest {
    private val symbol = SymbolAssignment(UUID.randomUUID(), UUID.randomUUID())
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val game = PlayRule.start(UUID.randomUUID(), symbol, now)

    @Test fun `every horizontal vertical and diagonal line wins for either mark`() {
        val line =
            listOf(
                listOf(0, 1, 2),
                listOf(3, 4, 5),
                listOf(6, 7, 8),
                listOf(0, 3, 6),
                listOf(1, 4, 7),
                listOf(2, 5, 8),
                listOf(0, 4, 8),
                listOf(2, 4, 6),
            )
        for (mark in listOf(Mark.X, Mark.O)) {
            for (cells in line) {
                var board = Board()
                cells.dropLast(1).forEach {
                    board = board.place(it, mark)
                    assertFalse(board.wins(mark))
                }
                assertTrue(board.place(cells.last(), mark).wins(mark))
                assertFalse(board.wins(if (mark == Mark.X) Mark.O else Mark.X))
            }
        }
    }

    @Test fun `winning last cell takes priority over a full board`() {
        val board = Board(listOf(Mark.X, Mark.O, Mark.X, Mark.O, Mark.X, Mark.O, Mark.O, Mark.X, Mark.EMPTY))
        val before = game.copy(board = board)
        val after = PlayRule.move(before, symbol.x, 8, now)
        assertEquals("WINNING_LINE", after.result?.reason)
        assertEquals(symbol.x, after.result?.winnerId)
        assertTrue(after.board.full())
    }

    @Test fun `classic drawn sequence has no winner and finished rejects every further action`() {
        val cells = listOf(0, 1, 2, 4, 3, 5, 7, 6, 8)
        var current = game
        cells.forEach { current = PlayRule.move(current, current.turnPlayer(), it, now) }
        assertEquals("BOARD_FULL", current.result?.reason)
        assertEquals("DRAW", current.result?.outcome)
        assertEquals(null, current.result?.winnerId)
        assertEquals(current, PlayRule.expire(current, now.plusSeconds(100)))
        assertThrows(PlayFailure::class.java) { PlayRule.move(current, symbol.x, 0, now) }
        assertThrows(PlayFailure::class.java) { PlayRule.surrender(current, symbol.o, now) }
    }

    @Test fun `symbol assignment is a permutation and the first move is always X`() {
        repeat(32) {
            val assigned = SymbolAssignment.random(listOf(symbol.x, symbol.o))
            assertEquals(setOf(symbol.x, symbol.o), setOf(assigned.x, assigned.o))
            val initial = PlayRule.start(UUID.randomUUID(), assigned, now)
            assertEquals(assigned.x, initial.turnPlayer())
            val after = PlayRule.move(initial, assigned.x, 0, now)
            assertEquals(assigned, after.symbol)
            assertEquals(PlayPhase.O_TURN, after.phase)
            assertEquals(Mark.X, after.board.cell[0])
        }
    }

    @Test fun `invalid board coordinates and an occupied cell never change the board`() {
        assertThrows(PlayFailure::class.java) { game.board.place(-1, Mark.X) }
        assertThrows(PlayFailure::class.java) { game.board.place(9, Mark.X) }
        val occupied = game.board.place(0, Mark.X)
        assertThrows(PlayFailure::class.java) { occupied.place(0, Mark.O) }
        assertEquals(Mark.X, occupied.cell[0])
        assertEquals(List(9) { Mark.EMPTY }, game.board.cell)
    }
}
