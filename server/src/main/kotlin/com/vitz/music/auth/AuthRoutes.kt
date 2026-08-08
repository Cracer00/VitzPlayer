package com.vitz.music.auth

import com.vitz.music.ApiException
import com.vitz.music.AppServices
import com.vitz.music.api.ChangePasswordRequest
import com.vitz.music.api.LoginRequest
import com.vitz.music.api.MeResponse
import com.vitz.music.api.RefreshRequest
import com.vitz.music.api.RegisterRequest
import com.vitz.music.api.TokenPair
import com.vitz.music.badRequest
import com.vitz.music.db.dbRead
import com.vitz.music.db.dbTx
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun Route.authRoutes(services: AppServices) = route("/api/v1") {
    val tokens = services.tokens
    val cfg = services.cfg

    post("/auth/register") {
        val body = call.receive<RegisterRequest>()
        if (!EMAIL_RE.matches(body.email.trim())) badRequest("Некорректный адрес почты")
        if (body.password.length < 8) badRequest("Пароль короче 8 символов")
        if (body.displayName.isBlank()) badRequest("Пустое имя")

        val pair = dbTx { c ->
            if (Users.findByEmail(c, body.email) != null) {
                throw ApiException(HttpStatusCode.Conflict, "email_taken", "Такая почта уже зарегистрирована")
            }
            val userId = Users.create(c, body.email, body.password, body.displayName, "user")
            if (!Users.consumeInvite(c, body.invite, userId)) {
                throw ApiException(HttpStatusCode.Forbidden, "invalid_invite", "Инвайт не найден или уже использован")
            }
            issue(services, c, userId, "user", body.device)
        }
        call.respond(pair)
    }

    post("/auth/login") {
        val body = call.receive<LoginRequest>()
        val ip = call.request.local.remoteHost
        LoginThrottle.check(Users.normalizeEmail(body.email), ip)

        val pair = dbTx { c ->
            val user = Users.findByEmail(c, body.email)
            val ok = user != null && user.disabledAt == null && Passwords.verify(body.password, user.passwordHash)
            if (user == null || !ok) {
                LoginThrottle.fail(Users.normalizeEmail(body.email), ip)
                throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Неверная почта или пароль")
            }
            LoginThrottle.success(Users.normalizeEmail(body.email), ip)
            issue(services, c, user.id, user.role, body.device)
        }
        call.respond(pair)
    }

    post("/auth/refresh") {
        val body = call.receive<RefreshRequest>()
        val hash = tokens.sha256Hex(body.refreshToken)
        val pair = dbTx { c ->
            val session = Users.sessionByRefresh(c, hash)
                ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_refresh", "Токен недействителен")
            val (sessionId, userId, role) = session
            val (raw, newHash) = tokens.newRefreshToken()
            Users.rotateRefresh(c, sessionId, newHash, cfg.refreshTtlDays)
            TokenPair(
                accessToken = tokens.issueAccess(userId, role, sessionId),
                refreshToken = raw,
                accessExpiresInSeconds = tokens.accessTtlSeconds,
            )
        }
        call.respond(pair)
    }

    authenticate("api") {
        post("/auth/logout") {
            val principal = call.principal<UserPrincipal>()!!
            dbTx { c -> Users.revokeSession(c, principal.sessionId) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/me/password") {
            val principal = call.principal<UserPrincipal>()!!
            val body = call.receive<ChangePasswordRequest>()
            if (body.newPassword.length < 8) badRequest("Новый пароль короче 8 символов")
            dbTx { c ->
                val user = Users.findById(c, principal.userId)
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized")
                if (!Passwords.verify(body.currentPassword, user.passwordHash)) {
                    throw ApiException(HttpStatusCode.Forbidden, "wrong_password", "Текущий пароль неверен")
                }
                Users.setPassword(c, user.id, body.newPassword)
                // Текущую сессию оставляем: иначе смена пароля выкидывает того, кто её и затеял.
                Users.revokeSessions(c, user.id, except = principal.sessionId)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val principal = call.principal<UserPrincipal>()!!
            val user = dbRead { c -> Users.findById(c, principal.userId) }
                ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized")
            call.respond(
                MeResponse(
                    id = user.id.toString(),
                    email = user.email,
                    displayName = user.displayName,
                    role = user.role,
                ),
            )
        }
    }
}

private fun issue(
    services: AppServices,
    c: java.sql.Connection,
    userId: UUID,
    role: String,
    device: String?,
): TokenPair {
    val (raw, hash) = services.tokens.newRefreshToken()
    val sessionId = Users.createSession(c, userId, hash, device, services.cfg.refreshTtlDays)
    return TokenPair(
        accessToken = services.tokens.issueAccess(userId, role, sessionId),
        refreshToken = raw,
        accessExpiresInSeconds = services.tokens.accessTtlSeconds,
    )
}

/**
 * Ограничитель попыток входа. В памяти процесса — этого достаточно: сервер один,
 * а перезапуск сбрасывает счётчики не чаще, чем раз в несколько недель.
 */
object LoginThrottle {
    private const val MAX_FAILURES = 10
    private const val WINDOW_MS = 5 * 60 * 1000L

    private data class Bucket(var failures: Int, var firstFailureAt: Long)

    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Bucket>()

    private fun keys(email: String, ip: String) = listOf("e:$email", "i:$ip")

    fun check(email: String, ip: String) {
        val now = System.currentTimeMillis()
        for (key in keys(email, ip)) {
            val bucket = buckets[key] ?: continue
            if (now - bucket.firstFailureAt > WINDOW_MS) {
                buckets.remove(key)
                continue
            }
            if (bucket.failures >= MAX_FAILURES) {
                throw ApiException(
                    HttpStatusCode.TooManyRequests,
                    "too_many_attempts",
                    "Слишком много попыток входа, попробуйте позже",
                )
            }
        }
    }

    fun fail(email: String, ip: String) {
        val now = System.currentTimeMillis()
        for (key in keys(email, ip)) {
            buckets.compute(key) { _, existing ->
                if (existing == null || now - existing.firstFailureAt > WINDOW_MS) Bucket(1, now)
                else existing.also { it.failures++ }
            }
        }
    }

    fun success(email: String, ip: String) {
        for (key in keys(email, ip)) buckets.remove(key)
    }
}
