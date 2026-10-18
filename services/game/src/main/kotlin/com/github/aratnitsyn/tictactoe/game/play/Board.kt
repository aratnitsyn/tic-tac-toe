package com.github.aratnitsyn.tictactoe.game.play

enum class Mark { EMPTY, X, O }

class PlayFailure(
    val code: String,
) : RuntimeException(code)

data class Board(
    val cell: List<Mark> = List(CELL_COUNT) { Mark.EMPTY },
) {
    init {
        require(cell.size == CELL_COUNT)
    }

    fun place(
        index: Int,
        mark: Mark,
    ): Board {
        if (index !in cell.indices || mark == Mark.EMPTY) throw PlayFailure("INVALID_MOVE")
        if (cell[index] != Mark.EMPTY) throw PlayFailure("CELL_OCCUPIED")
        return Board(cell.toMutableList().apply { this[index] = mark })
    }

    fun wins(mark: Mark): Boolean = LINE.any { line -> line.all { cell[it] == mark } }

    fun full(): Boolean = cell.none { it == Mark.EMPTY }
}

private const val CELL_COUNT = 9

// These are the eight winning lines of the agreed 3x3 board.
@Suppress("MagicNumber")
private val LINE =
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
