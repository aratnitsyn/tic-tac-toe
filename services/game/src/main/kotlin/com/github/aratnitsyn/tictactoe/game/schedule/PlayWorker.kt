package com.github.aratnitsyn.tictactoe.game.schedule

import com.github.aratnitsyn.tictactoe.game.play.PlayEngine
import com.github.aratnitsyn.tictactoe.game.play.PlayService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class PlayWorker(
    private val service: PlayService,
    private val engine: PlayEngine,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${coordination.retry-delay}")
    @Suppress("TooGenericExceptionCaught") // Lease expiry retries failed jobs after process or database faults.
    fun tick() {
        engine.deadlines.claim().forEach { task ->
            try {
                service.expire(task)
            } catch (failure: Exception) {
                logger.warn(
                    "Game deadline retry matchId={} kind={} generation={} failure={}",
                    task.matchId,
                    task.kind,
                    task.generation,
                    failure.javaClass.simpleName,
                )
            }
        }
    }
}
