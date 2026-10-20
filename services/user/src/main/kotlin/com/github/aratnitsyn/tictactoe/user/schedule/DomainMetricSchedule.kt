package com.github.aratnitsyn.tictactoe.user.schedule

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
        )
    private val value = query.mapValues { AtomicReference(0.0) }
    private val healthy = AtomicReference(0.0)

    init {
        value.forEach { (name, number) ->
            Gauge.builder(name, number) { it.get() }.tag("service", "user").register(registry)
        }
        Gauge.builder("ttt.observer.up", healthy) { it.get() }.tag("service", "user").register(registry)
    }

    @Scheduled(fixedDelayString = "\${METRIC_REFRESH_MS}")
    fun refresh() {
        // Mark stale samples explicitly; scheduler logs the database failure.
        healthy.set(0.0)
        query.forEach { (name, sql) -> value.getValue(name).set(jdbc.queryForObject(sql, Double::class.java) ?: 0.0) }
        healthy.set(1.0)
    }
}
