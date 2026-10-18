package com.github.aratnitsyn.tictactoe.lobby.room

import java.security.SecureRandom

class InvitationCode {
    private val random = SecureRandom()

    fun next(): String = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}

private const val CODE_LENGTH = 10
private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
