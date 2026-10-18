package com.github.aratnitsyn.tictactoe.game.preparation

import com.github.aratnitsyn.tictactoe.game.participation.CoordinationDatabase
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationEvent
import com.github.aratnitsyn.tictactoe.game.participation.Participation
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationState
import java.sql.Connection
import java.util.UUID

class PreparationReturn(
    private val db: CoordinationDatabase,
    private val events: PreparationEvent,
) {
    @Suppress("NestedBlockDepth") // Each member independently restores its source in the same cancellation transaction.
    fun cancel(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        disconnect: Boolean,
    ) {
        db.update(connection, "UPDATE game_match SET state='CANCELLED',version=version+1 WHERE match_id=?", match.id)
        db.update(connection, "UPDATE match_member SET active=false WHERE match_id=?", match.id)
        match.members.forEach { member ->
            val before = match.participation.single { it.userId == member.user }
            check(before.state == ParticipationState.IN_MATCH && before.resourceId == match.id)
            val returns = member.connected && (disconnect || member.ready)
            val target =
                if (!returns) {
                    ParticipationState.MENU
                } else if (match.source == "QUEUE") {
                    ParticipationState.QUEUED
                } else {
                    ParticipationState.LOBBY
                }
            val resource =
                when (target) {
                    ParticipationState.QUEUED -> requireNotNull(member.ticket)
                    ParticipationState.LOBBY -> match.sourceRef
                    else -> null
                }
            val after = change(connection, match, cause, before.copy(state = target, resourceId = resource))
            if (returns) {
                db.update(
                    connection,
                    "UPDATE match_member SET return_version=? WHERE match_id=? AND user_id=?",
                    after.version,
                    match.id,
                    member.user,
                )
                if (target == ParticipationState.QUEUED) {
                    val payload =
                        db.mapper.createObjectNode().apply {
                            put("userId", member.user.toString())
                            put("ticketId", resource.toString())
                            put("originalSequence", requireNotNull(member.sequence))
                            put("participationVersion", after.version)
                        }
                    events.emit(
                        connection,
                        match,
                        cause,
                        CoordinationEvent(
                            "ReturnToQueue",
                            "PARTICIPATION",
                            member.user,
                            after.version,
                            listOf(member.user),
                            payload,
                        ),
                    )
                } else {
                    restoreRoom(connection, match, member.user)
                }
            }
        }
    }

    // A second close can arrive after cancellation. Revoke only this unconsumed automatic return.
    fun revoke(
        connection: Connection,
        match: PreparedMatch,
        member: MatchMember,
        cause: UUID,
    ): Boolean {
        val before = match.participation.single { it.userId == member.user }
        val resource = if (match.source == "QUEUE") member.ticket else match.sourceRef
        val target = if (match.source == "QUEUE") ParticipationState.QUEUED else ParticipationState.LOBBY
        val staleVersion = member.returnVersion == null || before.version != member.returnVersion
        if (staleVersion ||
            before.state != target || before.resourceId != resource
        ) {
            return false
        }
        if (match.source == "LOBBY") {
            db.lock(connection, "room/${match.sourceRef}")
            db.update(connection, "DELETE FROM room_slot WHERE room_id=? AND user_id=?", match.sourceRef, member.user)
            db.update(connection, "UPDATE room_registration SET active=false WHERE room_id=?", match.sourceRef)
        }
        change(connection, match, cause, before.copy(state = ParticipationState.MENU, resourceId = null))
        db.update(connection, "UPDATE game_match SET version=version+1 WHERE match_id=?", match.id)
        return true
    }

    private fun restoreRoom(
        connection: Connection,
        match: PreparedMatch,
        user: UUID,
    ) {
        db.lock(connection, "room/${match.sourceRef}")
        db.update(
            connection,
            "UPDATE room_registration SET active=true,owner_id=? WHERE " +
                "room_id=?",
            user,
            match.sourceRef,
        )
        db.update(connection, "DELETE FROM room_slot WHERE room_id=?", match.sourceRef)
        db.update(connection, "INSERT INTO room_slot(room_id,slot,user_id) VALUES (?,1,?)", match.sourceRef, user)
    }

    private fun change(
        connection: Connection,
        match: PreparedMatch,
        cause: UUID,
        before: Participation,
    ): Participation {
        val after = before.copy(version = before.version + 1)
        check(
            db.update(
                connection,
                "UPDATE participation SET state=?,resource_id=?,version=? WHERE user_id=? AND operation_id IS NULL",
                after.state.name,
                after.resourceId,
                after.version,
                after.userId,
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
        return after
    }
}
