package com.vitz.music.admin

import io.ktor.server.sessions.SessionSerializer
import java.security.SecureRandom
import java.util.Base64

/**
 * Сессия админки. Сериализуется вручную: рефлексивные сериализаторы Ktor менялись от версии
 * к версии, а тут всего три поля.
 */
data class AdminSession(
    val userId: String,
    val csrf: String,
    val issuedAtEpochSeconds: Long,
) {
    object Serializer : SessionSerializer<AdminSession> {
        override fun serialize(session: AdminSession): String =
            listOf(session.userId, session.csrf, session.issuedAtEpochSeconds.toString()).joinToString("|")

        override fun deserialize(text: String): AdminSession {
            val parts = text.split('|')
            require(parts.size == 3) { "Испорченная сессия" }
            return AdminSession(parts[0], parts[1], parts[2].toLong())
        }
    }

    companion object {
        private val random = SecureRandom()

        fun newCsrf(): String {
            val bytes = ByteArray(24).also { random.nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
