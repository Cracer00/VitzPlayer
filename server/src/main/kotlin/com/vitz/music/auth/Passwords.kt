package com.vitz.music.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id на чистой Java (BouncyCastle) — без нативных библиотек, иначе образ пришлось бы
 * собирать под конкретную libc.
 *
 * Формат хранения: `argon2id$v=19$m=65536,t=3,p=1$<salt b64>$<hash b64>`.
 */
object Passwords {
    private const val MEMORY_KB = 65536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val HASH_LENGTH = 32
    private const val SALT_LENGTH = 16

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val hash = derive(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM)
        return "argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$" +
            "${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 5 || parts[0] != "argon2id") return false
        val params = parts[2].split(',').mapNotNull {
            val kv = it.split('=')
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val memory = params["m"]?.toIntOrNull() ?: return false
        val iterations = params["t"]?.toIntOrNull() ?: return false
        val parallelism = params["p"]?.toIntOrNull() ?: return false
        val salt = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[4]) }.getOrNull() ?: return false
        val actual = derive(password, salt, memory, iterations, parallelism, expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        memoryKb: Int,
        iterations: Int,
        parallelism: Int,
        length: Int = HASH_LENGTH,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(length)
        generator.generateBytes(password.toCharArray(), out)
        return out
    }
}
