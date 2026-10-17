package com.github.aratnitsyn.tictactoe.auth.account

import com.github.aratnitsyn.tictactoe.auth.account.security.EncryptedLoginResponse
import com.github.aratnitsyn.tictactoe.auth.account.security.LoginResponseCipher
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

class SessionIdentity(
    val userId: UUID,
    val sessionId: UUID,
    val expiresAt: Instant,
)

// No generated toString for a bearer credential.
class LoginResult(
    val identity: SessionIdentity,
    val token: String,
)

class SessionService(
    private val source: DataSource,
    private val hasher: PasswordHasher,
    private val cipher: LoginResponseCipher,
    private val time: (Connection) -> Instant = ::databaseTime,
) {
    private val dummyHash = hasher.hash(randomToken())

    @Suppress("MagicNumber") // JDBC positions and HTTP status constants.
    fun login(
        commandId: UUID,
        credentials: Credentials,
    ): LoginResult {
        val email = CredentialPolicy.email(credentials.email)
        val account = findAccount(email.key)
        if (!hasher.matches(credentials.password, account?.second ?: dummyHash) || account == null) {
            throw AuthFailure("UNAUTHENTICATED", 401)
        }
        return transaction { connection ->
            // Account lock exists even before the first slot is inserted.
            connection.prepareStatement("SELECT user_id FROM accounts WHERE user_id=? FOR UPDATE").use {
                it.setObject(1, account.first)
                it.execute()
            }
            connection.prepareStatement("SELECT user_id FROM session_slots WHERE user_id=? FOR UPDATE").use {
                it.setObject(1, account.first)
                it.execute()
            }
            val now = time(connection)
            val old = replay(connection, account.first, commandId, credentials.clientType, now)
            old ?: create(connection, account.first, commandId, credentials.clientType, now)
        }
    }

    @Suppress("MagicNumber") // JDBC positions.
    private fun findAccount(emailKey: String): Pair<UUID, String>? =
        source.connection.use { connection ->
            connection.prepareStatement("SELECT user_id,password_hash FROM accounts WHERE email_key=?").use {
                it.setString(1, emailKey)
                it.executeQuery().use { rows ->
                    readAccount(rows)
                }
            }
        }

    private fun readAccount(rows: ResultSet): Pair<UUID, String>? =
        if (rows.next()) rows.getObject("user_id", UUID::class.java) to rows.getString("password_hash") else null

    @Suppress("MagicNumber")
    private fun replay(
        connection: Connection,
        userId: UUID,
        commandId: UUID,
        client: ClientType,
        now: Instant,
    ): LoginResult? =
        connection
            .prepareStatement(
                "SELECT r.*,s.state,s.expires_at AS session_expiry,s.session_id AS current_session " +
                    "FROM login_responses r LEFT JOIN session_slots s ON s.user_id=r.user_id " +
                    "WHERE r.user_id=? AND r.command_id=?",
            ).use {
                it.setObject(1, userId)
                it.setObject(2, commandId)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    if (rows.getString("client_type") != client.name) throw AuthFailure("IDEMPOTENCY_CONFLICT", 409)
                    val replayValid =
                        now.isBefore(rows.getTimestamp("expires_at").toInstant()) &&
                            rows.getBytes("ciphertext") != null
                    val sessionValid =
                        rows.getString("state") == "ACTIVE" &&
                            now.isBefore(rows.getTimestamp("session_expiry").toInstant()) &&
                            rows.getObject("session_id") == rows.getObject("current_session")
                    if (!replayValid || !sessionValid) throw AuthFailure("UNAUTHENTICATED", 401)
                    val encrypted =
                        EncryptedLoginResponse(
                            rows.getString("key_id"),
                            rows.getBytes("nonce"),
                            rows.getBytes("ciphertext"),
                        )
                    val token = cipher.decrypt(userId, commandId, encrypted).toString(Charsets.UTF_8)
                    LoginResult(
                        SessionIdentity(
                            userId,
                            rows.getObject("session_id", UUID::class.java),
                            rows.getTimestamp("session_expiry").toInstant(),
                        ),
                        token,
                    )
                }
            }

    @Suppress("MagicNumber")
    private fun create(
        connection: Connection,
        userId: UUID,
        commandId: UUID,
        client: ClientType,
        now: Instant,
    ): LoginResult {
        connection.prepareStatement("SELECT state,expires_at FROM session_slots WHERE user_id=?").use {
            it.setObject(1, userId)
            it.executeQuery().use { rows ->
                checkSlot(rows, now)
            }
        }
        val sessionId = UUID.randomUUID()
        val token = randomToken()
        val expiry = now.plus(Duration.ofHours(24))
        connection
            .prepareStatement(
                "INSERT INTO session_slots VALUES (?,?,?,'ACTIVE',?,?) ON CONFLICT(user_id) DO UPDATE " +
                    "SET session_id=excluded.session_id,token_hash=excluded.token_hash,state='ACTIVE', " +
                    "last_activity=excluded.last_activity,expires_at=excluded.expires_at",
            ).use {
                it.setObject(1, userId)
                it.setObject(2, sessionId)
                it.setString(3, tokenHash(token))
                it.setTimestamp(4, Timestamp.from(now))
                it.setTimestamp(5, Timestamp.from(expiry))
                it.executeUpdate()
            }
        val encrypted = cipher.encrypt(userId, commandId, token.toByteArray(Charsets.UTF_8))
        connection.prepareStatement("INSERT INTO login_responses VALUES (?,?,?,?,?,?,?,?,?)").use {
            it.setObject(1, userId)
            it.setObject(2, commandId)
            it.setObject(3, sessionId)
            it.setString(4, client.name)
            it.setString(5, encrypted.keyId)
            it.setBytes(6, encrypted.nonce())
            it.setBytes(7, encrypted.ciphertext())
            it.setTimestamp(8, Timestamp.from(now))
            it.setTimestamp(9, Timestamp.from(expiry))
            it.executeUpdate()
        }
        return LoginResult(SessionIdentity(userId, sessionId, expiry), token)
    }

    @Suppress("MagicNumber") // HTTP conflict status.
    private fun checkSlot(
        rows: ResultSet,
        now: Instant,
    ) {
        if (!rows.next()) return
        val active = rows.getString("state") == "ACTIVE" && now.isBefore(rows.getTimestamp("expires_at").toInstant())
        if (rows.getString("state") == "CLOSING" || active) throw AuthFailure("SESSION_ALREADY_ACTIVE", 409)
    }

    @Suppress("MagicNumber")
    fun introspect(token: String): SessionIdentity? =
        transaction { connection ->
            connection.prepareStatement("SELECT * FROM session_slots WHERE token_hash=? FOR UPDATE").use {
                it.setString(1, tokenHash(token))
                it.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    // Read authority time after lock acquisition, including lock waits.
                    val now = time(connection)
                    val active = rows.getString("state") == "ACTIVE"
                    val unexpired = now.isBefore(rows.getTimestamp("expires_at").toInstant())
                    if (!active || !unexpired) return@use null
                    val identity = identity(rows, now.plus(Duration.ofHours(24)))
                    connection
                        .prepareStatement(
                            "UPDATE session_slots SET last_activity=?,expires_at=? WHERE user_id=?",
                        ).use { update ->
                            update.setTimestamp(1, Timestamp.from(now))
                            update.setTimestamp(2, Timestamp.from(identity.expiresAt))
                            update.setObject(3, identity.userId)
                            update.executeUpdate()
                        }
                    identity
                }
            }
        }

    @Suppress("MagicNumber") // JDBC parameter positions.
    fun touchIdentity(
        userId: UUID,
        sessionId: UUID,
    ): SessionIdentity? =
        transaction { connection ->
            connection.prepareStatement("SELECT * FROM session_slots WHERE user_id=? AND session_id=? FOR UPDATE").use {
                it.setObject(1, userId)
                it.setObject(2, sessionId)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    val now = time(connection)
                    val unexpired = now.isBefore(rows.getTimestamp("expires_at").toInstant())
                    if (rows.getString("state") != "ACTIVE" || !unexpired) return@use null
                    val expiry = now.plus(Duration.ofHours(24))
                    connection
                        .prepareStatement(
                            "UPDATE session_slots SET last_activity=?,expires_at=? WHERE " +
                                "user_id=?",
                        ).use { update ->
                            update.setTimestamp(1, Timestamp.from(now))
                            update.setTimestamp(2, Timestamp.from(expiry))
                            update.setObject(3, userId)
                            update.executeUpdate()
                        }
                    SessionIdentity(userId, sessionId, expiry)
                }
            }
        }

    private fun identity(
        rows: ResultSet,
        expiry: Instant,
    ) = SessionIdentity(
        rows.getObject("user_id", UUID::class.java),
        rows.getObject("session_id", UUID::class.java),
        expiry,
    )

    @Suppress("TooGenericExceptionCaught") // All transaction failures must roll back.
    private fun <T> transaction(effect: (Connection) -> T): T =
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                effect(connection).also { connection.commit() }
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
}

fun databaseTime(connection: Connection): Instant =
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            rows.next()
            rows.getTimestamp(1).toInstant()
        }
    }

@Suppress("MagicNumber") // 256-bit random credentials.
fun randomToken(): String =
    Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

fun tokenHash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(it)
    }

@Suppress("MagicNumber")
fun eraseExpired(
    connection: Connection,
    now: Instant,
) {
    // Retain only non-secret tombstones: expired command ids cannot create a new session on retry.
    connection
        .prepareStatement(
            "UPDATE login_responses SET key_id=NULL,nonce=NULL,ciphertext=NULL " +
                "WHERE expires_at<=? AND ciphertext IS NOT NULL",
        ).use {
            it.setTimestamp(1, Timestamp.from(now))
            it.executeUpdate()
        }
}
