package com.vitz.music

import com.vitz.music.ingest.Ffmpeg
import com.vitz.music.media.UrlSigner
import com.vitz.music.util.guessFromFileName
import com.vitz.music.util.normalizeName
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlSignerTest {
    private val signer = UrlSigner("0123456789abcdef0123456789abcdef", ttlSeconds = 3600)
    private val track = UUID.randomUUID()
    private val user = UUID.randomUUID()

    @Test
    fun `своя подпись проверяется`() {
        val exp = Instant.now().epochSecond + 60
        val sig = signer.sign(track, "opus-96", user, exp)
        assertTrue(signer.verify(track, "opus-96", user, exp, sig))
    }

    @Test
    fun `подпись привязана к треку рендиции и пользователю`() {
        val exp = Instant.now().epochSecond + 60
        val sig = signer.sign(track, "opus-96", user, exp)
        assertFalse(signer.verify(UUID.randomUUID(), "opus-96", user, exp, sig), "чужой трек")
        assertFalse(signer.verify(track, "original", user, exp, sig), "другая рендиция")
        assertFalse(signer.verify(track, "opus-96", UUID.randomUUID(), exp, sig), "другой пользователь")
    }

    @Test
    fun `просроченная ссылка не принимается`() {
        val exp = Instant.now().epochSecond - 1
        val sig = signer.sign(track, "opus-96", user, exp)
        assertFalse(signer.verify(track, "opus-96", user, exp, sig))
    }

    @Test
    fun `продление срока без пересчёта подписи не работает`() {
        val exp = Instant.now().epochSecond + 60
        val sig = signer.sign(track, "opus-96", user, exp)
        assertFalse(signer.verify(track, "opus-96", user, exp + 86400, sig))
    }

    @Test
    fun `ссылка содержит все параметры проверки`() {
        val url = signer.url("https://music.example.com", track, "opus-96", user)
        assertTrue(url.startsWith("https://music.example.com/media/t/$track/opus-96?"))
        listOf("u=", "exp=", "sig=").forEach { assertTrue(it in url, "в ссылке нет $it") }
    }
}

class LoudnessTest {

    @Test
    fun `тихий трек поднимается до цели`() {
        val gain = Ffmpeg.Loudness(inputI = -20.0, inputTp = -8.0, inputLra = 7.0, inputThresh = -30.0).gainToTarget()
        assertEquals(6.0, gain, 0.001)
    }

    @Test
    fun `подъём ограничен запасом до перегрузки`() {
        // До −14 LUFS не хватает 6 дБ, но пики уже на −2 dBTP: поднимаем только на 1 дБ.
        val gain = Ffmpeg.Loudness(inputI = -20.0, inputTp = -2.0, inputLra = 7.0, inputThresh = -30.0).gainToTarget()
        assertEquals(1.0, gain, 0.001)
    }

    @Test
    fun `громкий трек приглушается`() {
        val gain = Ffmpeg.Loudness(inputI = -8.0, inputTp = -0.2, inputLra = 5.0, inputThresh = -20.0).gainToTarget()
        assertTrue(gain < 0, "Ожидали приглушение, получили $gain")
    }
}

class NormTest {

    @Test
    fun `регистр диакритика и пунктуация не влияют`() {
        assertEquals(normalizeName("Björk"), normalizeName("bjork"))
        assertEquals(normalizeName("AC/DC"), normalizeName("ac dc"))
        assertEquals(normalizeName("  Кино   "), normalizeName("кино"))
    }

    @Test
    fun `ё и е считаются одной буквой`() {
        assertEquals(normalizeName("Ёлка"), normalizeName("Елка"))
    }

    @Test
    fun `имя файла разбирается на исполнителя и название`() {
        assertEquals("Кино" to "Группа крови", guessFromFileName("Кино - Группа крови.mp3"))
        assertEquals("Кино" to "Группа крови", guessFromFileName("03. Кино - Группа крови.flac"))
        assertEquals(null to "Просто трек", guessFromFileName("Просто трек.mp3"))
    }

    @Test
    fun `подчёркивания считаются пробелами`() {
        assertEquals("Sting" to "Fields Of Gold", guessFromFileName("Sting_-_Fields_Of_Gold.mp3"))
    }
}
