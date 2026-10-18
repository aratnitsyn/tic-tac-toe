package com.github.aratnitsyn.tictactoe.lobby.room

import org.springframework.http.HttpStatus

class CodeRateLimit(
    private val db: LobbyDatabase,
    private val userLimit: Int,
    private val ipLimit: Int,
) {
    init {
        require(userLimit > 0 && ipLimit > 0)
    }

    fun check(
        user: String,
        ip: String,
    ) {
        // Commit rejected attempts too; throwing inside the transaction would undo the counter.
        val allowed =
            db.transaction { connection ->
                listOf("ip/$ip" to ipLimit, "user/$user" to userLimit)
                    .map { (scope, limit) ->
                        connection
                            .prepareStatement(
                                "INSERT INTO code_attempt(scope,window_start,attempts) VALUES " +
                                    "(?,clock_timestamp(),1) " +
                                    "ON CONFLICT(scope) DO UPDATE SET " +
                                    "attempts=CASE WHEN code_attempt.window_start+interval '60 " +
                                    "seconds'<=clock_timestamp() " +
                                    "THEN 1 ELSE LEAST(code_attempt.attempts,2147483646)+1 END," +
                                    "window_start=CASE WHEN code_attempt.window_start+interval '60 " +
                                    "seconds'<=clock_timestamp() " +
                                    "THEN clock_timestamp() ELSE code_attempt.window_start END RETURNING attempts",
                            ).use {
                                it.setString(1, scope)
                                it.executeQuery().use { row ->
                                    row.next()
                                    row.getInt(1) <= limit
                                }
                            }
                    }.all { it }
            }
        if (!allowed) throw LobbyFailure("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS.value())
    }
}
