package com.github.aratnitsyn.tictactoe.auth.account

import org.apache.commons.validator.routines.EmailValidator
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.Locale

class AuthFailure(
    val code: String,
    val status: Int = 400,
    val reason: String = code,
) : RuntimeException(reason)

enum class ClientType { WEB, NATIVE }

// No generated toString: requests contain a secret and must not be logged.
class Credential(
    val email: String,
    val password: String,
    val clientType: ClientType,
)

data class EmailAddress(
    val original: String,
    val key: String,
)

object CredentialPolicy {
    private const val SPECIAL = "!@#$%^&*()_+-=[]{};:,.?"

    @Suppress("MagicNumber") // Product policy, inclusive length and ASCII ranges.
    fun email(raw: String): EmailAddress {
        val value = raw.trim()
        if (value.length > 254 || value.any { it.code !in 33..126 } ||
            !EmailValidator.getInstance(false, false).isValid(value)
        ) {
            throw AuthFailure("VALIDATION_ERROR", reason = "Invalid email format")
        }
        return EmailAddress(value, value.lowercase(Locale.ROOT))
    }

    @Suppress("MagicNumber") // Product password policy: six to fifteen ASCII characters.
    fun password(value: String) {
        val categories =
            listOf(
                value.any { it in 'a'..'z' },
                value.any { it in 'A'..'Z' },
                value.any { it in '0'..'9' },
                value.any { it in SPECIAL },
            )
        val allowed = value.all { it.isLetterOrDigit() && it.code < 128 || it in SPECIAL }
        if (value.length !in 6..15 || !categories.all { it } || !allowed) {
            throw AuthFailure(
                "VALIDATION_ERROR",
                reason = "Password requires 6-15 allowed ASCII characters: lowercase, uppercase, digit and special",
            )
        }
    }
}

class PasswordHasher {
    // OWASP floor: Argon2id, 19 MiB, two iterations, one lane; encoded hash includes salt/parameters.
    @Suppress("MagicNumber")
    private val encoder = Argon2PasswordEncoder(16, 32, 1, 19456, 2)

    fun hash(password: String): String = encoder.encode(password)

    fun matches(
        password: String,
        encoded: String,
    ): Boolean = encoder.matches(password, encoded)
}
