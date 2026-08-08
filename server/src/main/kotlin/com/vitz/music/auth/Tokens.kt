package com.vitz.music.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.vitz.music.Config
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

const val JWT_ISSUER = "vitz-music"
const val JWT_AUDIENCE = "vitz-music-client"

class Tokens(private val cfg: Config) {
    private val algorithm: Algorithm = Algorithm.HMAC256(cfg.jwtSecret)
    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(JWT_ISSUER)
        .withAudience(JWT_AUDIENCE)
        .build()

    val accessTtlSeconds: Long get() = cfg.accessTtlMinutes * 60

    fun issueAccess(userId: UUID, role: String, sessionId: UUID): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(JWT_ISSUER)
            .withAudience(JWT_AUDIENCE)
            .withSubject(userId.toString())
            .withClaim("role", role)
            .withClaim("sid", sessionId.toString())
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(accessTtlSeconds))
            .sign(algorithm)
    }

    /** Refresh-токен: наружу — случайные 32 байта, в базе — только их sha256. */
    fun newRefreshToken(): Pair<String, String> {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val token = urlEncoder.encodeToString(raw)
        return token to sha256Hex(token)
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
