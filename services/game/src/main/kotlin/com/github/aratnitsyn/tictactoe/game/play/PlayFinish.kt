package com.github.aratnitsyn.tictactoe.game.play

import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationEvent
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationState
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationEvent
import com.github.aratnitsyn.tictactoe.game.preparation.PreparedMatch
import java.sql.Connection
import java.util.UUID

class PlayFinish(
    private val db: CoordinationDatabase,
    private val events: PreparationEvent,
) {
    fun apply(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
    ) {
        check(match.state == "RUNNING")
        db.update(connection, "UPDATE game_match SET state='FINISHED',version=version+1 WHERE match_id=?", match.id)
        db.update(connection, "UPDATE match_member SET active=false WHERE match_id=?", match.id)
        match.participation.forEach { before ->
            check(before.state == ParticipationState.IN_MATCH && before.resourceId == match.id)
            val after = before.copy(state = ParticipationState.MENU, resourceId = null, version = before.version + 1)
            check(
                db.update(
                    connection,
                    "UPDATE participation SET state='MENU',resource_id=NULL,version=? " +
                        "WHERE user_id=? AND operation_id IS NULL AND resource_id=?",
                    after.version,
                    after.userId,
                    match.id,
                ) == 1,
            )
            events.emit(
                connection,
                match,
                cause,
                CoordinationEvent(
                    "ParticipationChanged",
                    "PARTICIPATION",
                    after.userId,
                    after.version,
                    listOf(after.userId),
                    db.participationJson(after),
                ),
            )
        }
    }
}
