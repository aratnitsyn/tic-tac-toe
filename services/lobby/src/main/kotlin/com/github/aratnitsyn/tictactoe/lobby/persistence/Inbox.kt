package com.github.aratnitsyn.tictactoe.lobby.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.Message
import io.nats.client.PublishOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

private const val DEFAULT_MAX_ATTEMPTS = 5L

@Suppress("MagicNumber") // Configured retry schedule, not domain game deadlines.
private val DEFAULT_RETRY_DELAYS = listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30))

class Inbox(
    private val dataSource: DataSource,
) {
    // A transaction boundary rolls back and rethrows any callback failure.
    @Suppress("TooGenericExceptionCaught")
    fun process(
        consumer: String,
        eventId: UUID,
        effect: (Connection) -> Unit,
    ): Boolean {
        require(consumer.isNotBlank())
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val first =
                    connection
                        .prepareStatement(
                            "INSERT INTO inbox(consumer_name,event_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                        ).use {
                            it.setString(1, consumer)
                            it.setObject(2, eventId)
                            it.executeUpdate() == 1
                        }
                if (first) effect(connection)
                connection.commit()
                first
            } catch (failure: Exception) {
                try {
                    connection.rollback()
                } catch (rollback: Exception) {
                    failure.addSuppressed(rollback)
                }
                throw failure
            }
        }
    }
}

enum class DeliveryResult { ACKED, RETRY, PARKED }

class InboxDelivery(
    private val inbox: Inbox,
    private val jetStream: JetStream,
    private val consumer: String,
    private val effect: (Connection, ByteArray) -> Unit,
    private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
    private val maxAttempts: Long = DEFAULT_MAX_ATTEMPTS,
) {
    private val mapper = ObjectMapper()

    init {
        require(retryDelays.isNotEmpty() && retryDelays.none { it.isNegative })
        require(maxAttempts > 0)
        require(consumer.matches(Regex("[A-Za-z0-9_-]+")))
    }

    // Parsing, database and acknowledgement failures all retain the original message.
    @Suppress("TooGenericExceptionCaught")
    fun handle(message: Message): DeliveryResult =
        try {
            val eventId = UUID.fromString(mapper.readTree(message.data)["eventId"].asText())
            inbox.process(consumer, eventId) { connection -> effect(connection, message.data) }
            message.ack()
            DeliveryResult.ACKED
        } catch (_: Exception) {
            retryOrPark(message)
        }

    // Five application attempts; broker maxDeliver must remain unlimited for failed parking retries.
    @Suppress("MagicNumber", "TooGenericExceptionCaught")
    private fun retryOrPark(message: Message): DeliveryResult {
        val attempt = message.metaData().deliveredCount()
        val delay = retryDelays[minOf(attempt.toInt() - 1, retryDelays.lastIndex).coerceAtLeast(0)]
        if (attempt < maxAttempts) {
            message.nakWithDelay(delay)
            return DeliveryResult.RETRY
        }
        return try {
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(message.data)
                    .joinToString("") { "%02x".format(it) }
            val payload =
                mapper
                    .createObjectNode()
                    .put("consumer", consumer)
                    .put("originalSubject", message.subject)
                    .put("payloadBase64", Base64.getEncoder().encodeToString(message.data))
            jetStream.publish(
                "parking.$consumer",
                payload.toString().toByteArray(Charsets.UTF_8),
                PublishOptions.builder().messageId("$consumer/$hash").build(),
            )
            message.term()
            DeliveryResult.PARKED
        } catch (failure: Exception) {
            message.nakWithDelay(delay)
            throw failure
        }
    }
}

@Suppress("MagicNumber") // Protocol configuration: acknowledgement timeout and bounded pending count.
fun registerInboxConsumer(
    management: JetStreamManagement,
    stream: String,
    consumer: String,
    filter: String,
) {
    management.addOrUpdateConsumer(
        stream,
        ConsumerConfiguration
            .builder()
            .durable(consumer)
            .filterSubject(filter)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofSeconds(30))
            .maxDeliver(-1)
            .maxAckPending(256)
            .build(),
    )
}
