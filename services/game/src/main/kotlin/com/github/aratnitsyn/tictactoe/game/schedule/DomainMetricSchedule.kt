package com.github.aratnitsyn.tictactoe.game.schedule

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

@Component
class DomainMetricSchedule(
    private val jdbc: JdbcTemplate,
    registry: MeterRegistry,
) {
    private val query =
        mapOf(
            "ttt.outbox.pending" to "SELECT count(*) FROM outbox WHERE published_at IS NULL",
            "ttt.outbox.lag.seconds" to
                "SELECT coalesce(extract(epoch" +
                " FROM clock_timestamp()-min(created_at)),0) FROM outbox WHERE published_at IS NULL",
            "ttt.game.active" to "SELECT count(*) FROM game WHERE state<>'FINISHED'",
            "ttt.game.paused" to "SELECT count(*) FROM game WHERE state='PAUSED'",
            "ttt.timer.due" to "SELECT count(*) FROM game_deadline WHERE due_at<=clock_timestamp()",
            "ttt.timer.lag.seconds" to
                "SELECT coalesce(greatest(extract(epoch" +
                " FROM clock_timestamp()-min(due_at)),0),0) FROM game_deadline",
        )
    private val value = query.mapValues { AtomicReference(0.0) }
    private val healthy = AtomicReference(0.0)

    init {
        value.forEach { (name, number) ->
            Gauge.builder(name, number) { it.get() }.tag("service", "game").register(registry)
        }
        Gauge.builder("ttt.observer.up", healthy) { it.get() }.tag("service", "game").register(registry)
    }

    @Scheduled(fixedDelayString = "\${METRIC_REFRESH_MS}")
    fun refresh() {
        // Mark stale samples explicitly; scheduler logs the database failure.
        healthy.set(0.0)
        query.forEach { (name, sql) -> value.getValue(name).set(jdbc.queryForObject(sql, Double::class.java) ?: 0.0) }
        healthy.set(1.0)
    }
}
