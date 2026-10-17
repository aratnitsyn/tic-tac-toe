package com.github.aratnitsyn.tictactoe.auth.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class LoginResponseCipherTest {
    private val userId = UUID.randomUUID()
    private val commandId = UUID.randomUUID()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = LoginResponseCipher("test-key", { key })
    private val response = "synthetic-test-response".toByteArray()

    @Test
    fun `encrypts with a fresh nonce and restores the exact response`() {
        val first = cipher.encrypt(userId, commandId, response)
        val second = cipher.encrypt(userId, commandId, response)
        assertFalse(first.nonce().contentEquals(second.nonce()))
        assertFalse(first.ciphertext().contentEquals(response))
        assertArrayEquals(response, cipher.decrypt(userId, commandId, first))
    }

    @Test
    fun `rejects another user or command and modified ciphertext`() {
        val stored = cipher.encrypt(userId, commandId, response)
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(UUID.randomUUID(), commandId, stored) }
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(userId, UUID.randomUUID(), stored) }
        val modified = stored.ciphertext().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val corrupted = EncryptedLoginResponse(stored.keyId, stored.nonce(), modified)
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(userId, commandId, corrupted) }
    }

    @Test
    fun `key rotation can read ciphertext using its original key identifier`() {
        val stored = cipher.encrypt(userId, commandId, response)
        val nextKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keys = mapOf("test-key" to key, "next-key" to nextKey)
        val rotated = LoginResponseCipher("next-key", { keys.getValue(it) })
        assertArrayEquals(response, rotated.decrypt(userId, commandId, stored))
        val newlyStored = rotated.encrypt(userId, commandId, response)
        assertArrayEquals(response, rotated.decrypt(userId, commandId, newlyStored))
    }
}
