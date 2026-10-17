package com.github.aratnitsyn.tictactoe.auth.account.security

import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val NONCE_BYTES = 12
private const val TAG_BITS = 128
private const val KEY_BYTES = 32

// Deliberately not a data class: generated toString must never disclose stored bytes.
class EncryptedLoginResponse(
    val keyId: String,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    fun nonce(): ByteArray = nonceBytes.copyOf()

    fun ciphertext(): ByteArray = ciphertextBytes.copyOf()
}

class LoginResponseCipher(
    private val activeKeyId: String,
    private val keyProvider: (String) -> SecretKey,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(activeKeyId.isNotBlank())
    }

    fun encrypt(
        userId: UUID,
        commandId: UUID,
        response: ByteArray,
    ): EncryptedLoginResponse {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, activeKeyId, nonce, userId, commandId)
        return EncryptedLoginResponse(activeKeyId, nonce, cipher.doFinal(response))
    }

    fun decrypt(
        userId: UUID,
        commandId: UUID,
        response: EncryptedLoginResponse,
    ): ByteArray =
        cipher(Cipher.DECRYPT_MODE, response.keyId, response.nonce(), userId, commandId)
            .doFinal(response.ciphertext())

    private fun cipher(
        mode: Int,
        keyId: String,
        nonce: ByteArray,
        userId: UUID,
        commandId: UUID,
    ): Cipher {
        require(nonce.size == NONCE_BYTES)
        val key = keyProvider(keyId)
        require(key.algorithm == "AES" && key.encoded.size == KEY_BYTES)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD("login-response/v1/$keyId/$userId/$commandId".toByteArray(Charsets.UTF_8))
        }
    }
}
