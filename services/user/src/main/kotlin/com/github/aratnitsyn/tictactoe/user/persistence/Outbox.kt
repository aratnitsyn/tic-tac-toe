package com.github.aratnitsyn.tictactoe.user.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class OutboxEvent(
    val id: UUID,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val type: String,
    val envelope: JsonNode,
)

fun interface EventPublisher {
    fun publish(event: OutboxEvent)
}

class JetStreamPublisher(
    private val jetStream: JetStream,
    private val subjectPrefix: String = "events",
) : EventPublisher {
    override fun publish(event: OutboxEvent) {
        // Synchronous JetStream publish returns only after the publish acknowledgement.
        jetStream.publish(
            "$subjectPrefix.${event.type}",
            event.envelope.toString().toByteArray(Charsets.UTF_8),
            PublishOptions.builder().messageId(event.id.toString()).build(),
        )
    }
}

object Outbox {
    // JDBC parameter positions follow the INSERT columns below.
    @Suppress("MagicNumber")
    fun enqueue(
        connection: Connection,
        event: OutboxEvent,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO outbox(event_id, aggregate_id, aggregate_version, event_type, envelope) " +
                    "VALUES (?, ?, ?, ?, ?::jsonb)",
            ).use {
                it.setObject(1, event.id)
                it.setObject(2, event.aggregateId)
                it.setLong(3, event.aggregateVersion)
                it.setString(4, event.type)
                it.setString(5, event.envelope.toString())
                it.executeUpdate()
            }
    }
}

class OutboxWorker(
    private val dataSource: DataSource,
    private val publisher: EventPublisher,
    private val owner: String,
    private val afterAck: () -> Unit = {},
) {
    private val mapper = ObjectMapper()

    init {
        require(owner.isNotBlank())
    }

    fun publishOne(): Boolean {
        val event = claim() ?: return false
        publisher.publish(event)
        afterAck()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE outbox SET published_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL " +
                        "WHERE event_id=? AND lease_owner=? AND published_at IS NULL",
                ).use {
                    it.setObject(1, event.id)
                    it.setString(2, owner)
                    check(it.executeUpdate() == 1) { "Outbox lease lost" }
                }
        }
        return true
    }

    private fun claim(): OutboxEvent? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE outbox SET lease_owner=?, " +
                        "lease_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1 " +
                        "WHERE event_id=(SELECT event_id FROM outbox WHERE published_at IS NULL " +
                        "AND (lease_until IS NULL OR lease_until<=clock_timestamp()) " +
                        "ORDER BY created_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1) " +
                        "RETURNING event_id,aggregate_id,aggregate_version,event_type,envelope",
                ).use {
                    it.setString(1, owner)
                    it.executeQuery().use { rows ->
                        readEvent(rows)
                    }
                }
        }

    private fun readEvent(rows: java.sql.ResultSet): OutboxEvent? {
        if (!rows.next()) return null
        return OutboxEvent(
            rows.getObject("event_id", UUID::class.java),
            rows.getObject("aggregate_id", UUID::class.java),
            rows.getLong("aggregate_version"),
            rows.getString("event_type"),
            mapper.readTree(rows.getString("envelope")),
        )
    }
}
