package com.github.aratnitsyn.tictactoe.game.connection

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import com.github.aratnitsyn.tictactoe.game.preparation.PreparationStore
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@Suppress("MagicNumber") // Batch limit and JDBC column positions.
class ConnectionLease(
    private val store: PreparationStore,
    private val preparation: PreparationService,
) {
    fun renew(payload: JsonNode): JsonNode {
        require(payload.isObject && payload.size() == 2 && payload.path("operationId").isTextual)
        UUID.fromString(payload["operationId"].asText())
        require(payload.path("connections").isArray && payload["connections"].size() <= 1000)
        val accepted = store.db.mapper.createArrayNode()
        payload["connections"].forEach { item ->
            require(
                item.isObject && item.size() == 3 && item.path("userId").isTextual &&
                    item.path("matchId").isTextual && item.path("generation").isIntegralNumber,
            )
            val user = UUID.fromString(item.path("userId").asText())
            val match = UUID.fromString(item.path("matchId").asText())
            val generation = item.path("generation").longValue()
            require(generation >= 0)
            store.db.transaction { connection ->
                val current = store.lock(connection, match)
                require(current.members.any { it.user == user })
                preparation.advance(connection, current, UUID.randomUUID())
                if (store.lock(connection, match).state == "FINISHED") {
                    accepted.add(item)
                    return@transaction
                }
                val changed =
                    store.db.update(
                        connection,
                        "UPDATE match_member SET connection_lease_until=clock_timestamp()+interval '6 seconds' " +
                            "WHERE match_id=? AND user_id=? AND connection_generation=? AND connected " +
                            "AND connection_lease_until>clock_timestamp()",
                        match,
                        user,
                        generation,
                    )
                if (changed == 1 || current.state == "FINISHED") accepted.add(item)
            }
        }
        return store.db.mapper
            .createObjectNode()
            .set("connections", accepted)
    }

    fun expire() {
        val expired =
            store.db.source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT match_id,user_id,connection_generation FROM match_member " +
                                "JOIN game_match USING(match_id) " +
                                "WHERE game_match.state<>'FINISHED' AND connected " +
                                "AND connection_lease_until<=clock_timestamp() LIMIT 100",
                        ).use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(
                                        Triple(
                                            rows.getObject(1, UUID::class.java),
                                            rows.getObject(2, UUID::class.java),
                                            rows.getLong(3),
                                        ),
                                    )
                                }
                            }
                        }
                }
            }
        expired.forEach { (match, user, generation) ->
            val command = UUID.nameUUIDFromBytes("lease/$match/$user/$generation".toByteArray())
            val payload =
                store.db.mapper
                    .createObjectNode()
                    .put("operationId", command.toString())
                    .put("userId", user.toString())
                    .put("matchId", match.toString())
                    .put("generation", generation)
                    .put("connected", false)
            preparation.observe(user, match, command, payload)
        }
    }
}
