package com.github.aratnitsyn.tictactoe.auth.account

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class PasswordBenchmarkTest {
    @Test
    fun `measure argon2id on the current test runtime`() {
        val hasher = PasswordHasher()
        val password =
            com.github.aratnitsyn.tictactoe.auth.account
                .randomToken()
                .take(12)
        repeat(3) { hasher.hash(password) }
        val samples =
            (1..20)
                .map {
                    val start = System.nanoTime()
                    val encoded = hasher.hash(password)
                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
                    assertTrue(hasher.matches(password, encoded))
                    elapsed
                }.sorted()
        println("Argon2id benchmark: memoryKiB=19456 iterations=2 parallelism=1 samples=${samples.size}")
        val runtime = Runtime.getRuntime()
        val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-Xm") }
        val platform = listOf("java.version", "os.name", "os.arch").associateWith(System::getProperty)
        println("Platform=$platform CPUs=${runtime.availableProcessors()}")
        println("JVMHeapMax=${runtime.maxMemory()} JVMArgs=$jvmArgs")
        println("Hash milliseconds: p50=${samples[9]} p95=${samples[18]} max=${samples.last()}")
    }
}
