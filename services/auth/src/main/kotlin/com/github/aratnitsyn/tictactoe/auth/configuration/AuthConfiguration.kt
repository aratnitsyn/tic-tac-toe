package com.github.aratnitsyn.tictactoe.auth.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.PasswordHasher
import com.github.aratnitsyn.tictactoe.auth.account.RegistrationService
import com.github.aratnitsyn.tictactoe.auth.account.SessionService
import com.github.aratnitsyn.tictactoe.auth.account.security.LoginResponseCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@Configuration
class AuthConfiguration {
    @Bean
    fun passwordHasher() = PasswordHasher()

    @Bean
    fun registrationService(
        source: DataSource,
        hasher: PasswordHasher,
        mapper: ObjectMapper,
    ) = RegistrationService(source, hasher, mapper)

    @Bean
    fun loginCipher(
        @Value("\${auth.login-key-file}") keyFile: String,
        @Value("\${auth.login-key-id}") keyId: String,
    ): LoginResponseCipher {
        // Mounted secret file: keyId=base64 AES key. Keep old keys until their last replay expires.
        val keys = Properties().apply { Files.newInputStream(Path.of(keyFile)).use(::load) }
        return LoginResponseCipher(keyId, { id ->
            val encoded = requireNotNull(keys.getProperty(id))
            SecretKeySpec(Base64.getDecoder().decode(encoded), "AES")
        })
    }

    @Bean
    fun sessionService(
        source: DataSource,
        hasher: PasswordHasher,
        cipher: LoginResponseCipher,
    ) = SessionService(source, hasher, cipher)
}
