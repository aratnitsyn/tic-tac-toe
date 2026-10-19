package com.github.aratnitsyn.tictactoe.game.schedule

import com.github.aratnitsyn.tictactoe.game.connection.ConnectionLease
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ConnectionWorker(
    private val lease: ConnectionLease,
) {
    @Scheduled(fixedDelayString = "\${coordination.retry-delay}")
    fun expire() = lease.expire()
}
