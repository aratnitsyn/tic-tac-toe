package com.github.aratnitsyn.tictactoe.game.connection

import com.github.aratnitsyn.tictactoe.game.play.PlayTestFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class ConnectionLeaseTest {
    private fun saved(f: PlayTestFixture) =
        f.db.source.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT connection_lease_until FROM match_member WHERE match_id=? AND user_id=?",
                ).use { statement ->
                    statement.setObject(1, f.id)
                    statement.setObject(2, f.a)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getTimestamp(1)
                    }
                }
        }

    @Test
    fun `finished match acknowledges renew without changing stored lease`() {
        PlayTestFixture().use { f ->
            f.observe(f.a, true, 1)
            f.surrender()

            val before = saved(f)
            val payload =
                f.mapper.createObjectNode().put(
                    "operationId",
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
            payload
                .putArray("connections")
                .addObject()
                .put("userId", f.a.toString())
                .put("matchId", f.id.toString())
                .put("generation", 1)
            assertEquals(1, ConnectionLease(f.matches, f.preparation).renew(payload)["connections"].size())
            assertEquals(before, saved(f))
        }
    }

    @Test
    fun `persisted lease expires once after worker recreation and older generation is fenced`() {
        PlayTestFixture().use { f ->
            f.observe(f.a, true, 1)
            val lease = ConnectionLease(f.matches, f.preparation)
            val payload =
                f.mapper.createObjectNode().put(
                    "operationId",
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
            payload
                .putArray("connections")
                .addObject()
                .put("userId", f.a.toString())
                .put("matchId", f.id.toString())
                .put("generation", 1)
            assertEquals(1, lease.renew(payload)["connections"].size())
            f.db.transaction { connection ->
                f.db.update(
                    connection,
                    "UPDATE match_member SET connection_lease_until=clock_timestamp()-interval '1 second' " +
                        "WHERE match_id=? AND user_id=?",
                    f.id,
                    f.a,
                )
            }
            assertEquals(0, lease.renew(payload)["connections"].size())
            ConnectionLease(f.matches, f.preparation).expire()
            val paused = f.game()
            assertEquals("PAUSED", paused.phase.name)
            assertEquals("IN_MATCH", f.state(f.a)["state"].asText())
            ConnectionLease(f.matches, f.preparation).expire()
            assertEquals(paused, f.game())
            f.observe(f.a, true, 2)
            f.observe(f.a, false, 1)
            assertEquals("X_TURN", f.game().phase.name)
        }
    }

    @Test
    fun `unobserved socket death cancels readiness without game or loss`() {
        PlayTestFixture(false).use { f ->
            f.db.transaction { connection ->
                f.db.update(
                    connection,
                    "UPDATE match_member SET connection_lease_until=clock_timestamp()-interval '1 second' " +
                        "WHERE match_id=? AND user_id=?",
                    f.id,
                    f.a,
                )
            }
            ConnectionLease(f.matches, f.preparation).expire()
            assertEquals("MENU", f.state(f.a)["state"].asText())
            assertEquals("QUEUED", f.state(f.b)["state"].asText())
            assertEquals("CANCELLED", f.preparation.snapshot(f.a, f.id)["data"]["state"].asText())
        }
    }
}
