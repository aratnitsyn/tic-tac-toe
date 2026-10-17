package com.github.aratnitsyn.tictactoe.auth.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialPolicyTest {
    @Test
    fun `password requires all four ASCII categories without normalization`() {
        listOf("Abc1!x", "Abcdefghijk12!X").forEach(CredentialPolicy::password)
        listOf(
            "Ab1!x",
            "Abcdefghijkl12!X",
            "abc1!x",
            "ABC1!X",
            "Abcd!x",
            "Abc12x",
            "Abc1! ",
            "Abc1!я",
            " Abc1!x",
        ).forEach {
            assertThrows(AuthFailure::class.java) { CredentialPolicy.password(it) }
        }
    }

    @Test
    fun `email trims and folds case but preserves dot and plus`() {
        val email = CredentialPolicy.email("  Alice.Example+tag@Example.COM  ")
        assertEquals("Alice.Example+tag@Example.COM", email.original)
        assertEquals("alice.example+tag@example.com", email.key)
        listOf(
            "a",
            "a@",
            "@example.com",
            "a@@example.com",
            "a b@example.com",
            "я@example.com",
            "a@localhost",
            "a@-example.com",
        ).forEach {
            assertThrows(AuthFailure::class.java) { CredentialPolicy.email(it) }
        }
    }

    @Test
    fun `argon2id has unique salts embedded parameters and no plaintext`() {
        val hasher = PasswordHasher()
        val first = hasher.hash("Abc1!x")
        assertNotEquals(first, hasher.hash("Abc1!x"))
        assertTrue(first.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertFalse(first.contains("Abc1!x"))
        assertTrue(hasher.matches("Abc1!x", first))
        assertFalse(hasher.matches("Abc2!x", first))
        assertFalse(Credential("a@example.com", "Abc1!x", ClientType.NATIVE).toString().contains("Abc1!x"))
    }
}
