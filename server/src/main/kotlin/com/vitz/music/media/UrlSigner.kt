package com.vitz.music.media

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подписанные ссылки на медиа: ссылку можно отдать плееру, менеджеру загрузок и тегу `<audio>`
 * одинаково, не протаскивая заголовок Authorization через каждый слой. Протёкшая ссылка живёт
 * часы и привязана к конкретному пользователю.
 */
class UrlSigner(secret: String, private val ttlSeconds: Long) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun sign(trackId: UUID, rendition: String, userId: UUID, expiresAt: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val payload = "$trackId|$rendition|$userId|$expiresAt"
        return encoder.encodeToString(mac.doFinal(payload.toByteArray()))
    }

    fun verify(trackId: UUID, rendition: String, userId: UUID, expiresAt: Long, signature: String): Boolean {
        if (expiresAt < Instant.now().epochSecond) return false
        val expected = sign(trackId, rendition, userId, expiresAt)
        return MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())
    }

    fun url(publicUrl: String, trackId: UUID, rendition: String, userId: UUID): String {
        val exp = Instant.now().epochSecond + ttlSeconds
        val sig = sign(trackId, rendition, userId, exp)
        return "$publicUrl/media/t/$trackId/$rendition?u=$userId&exp=$exp&sig=$sig"
    }
}
