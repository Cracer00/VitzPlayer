package com.vitz.music.auth

import com.vitz.music.Config
import com.vitz.music.db.Db
import com.vitz.music.db.dbRead
import com.vitz.music.db.exec
import com.vitz.music.db.instantOrNull
import com.vitz.music.db.select
import com.vitz.music.db.selectOne
import com.vitz.music.db.uuid
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class UserPrincipal(val userId: UUID, val role: String, val sessionId: UUID) {
    val isAdmin: Boolean get() = role == "admin"
}

data class UserRow(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: String,
    val passwordHash: String,
    val disabledAt: Instant?,
)

data class InviteRow(
    val code: String,
    val note: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val usedBy: UUID?,
    val usedAt: Instant?,
)

object Users {
    private val log = LoggerFactory.getLogger(Users::class.java)

    fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

    private fun Connection.userBy(where: String, arg: Any?): UserRow? = selectOne(
        "select id, email, display_name, role, password_hash, disabled_at from users where $where",
        arg,
    ) { rs ->
        UserRow(
            id = rs.uuid("id"),
            email = rs.getString("email"),
            displayName = rs.getString("display_name"),
            role = rs.getString("role"),
            passwordHash = rs.getString("password_hash"),
            disabledAt = rs.instantOrNull("disabled_at"),
        )
    }

    fun findByEmail(c: Connection, email: String): UserRow? = c.userBy("email_norm = ?", normalizeEmail(email))

    fun findById(c: Connection, id: UUID): UserRow? = c.userBy("id = ?", id)

    fun create(c: Connection, email: String, password: String, displayName: String, role: String): UUID {
        val id = UUID.randomUUID()
        c.exec(
            """
            insert into users (id, email, email_norm, password_hash, display_name, role)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, email.trim(), normalizeEmail(email), Passwords.hash(password), displayName.trim(), role,
        )
        return id
    }

    fun list(c: Connection): List<UserRow> = c.select(
        "select id, email, display_name, role, password_hash, disabled_at from users order by created_at",
    ) { rs ->
        UserRow(
            id = rs.uuid("id"),
            email = rs.getString("email"),
            displayName = rs.getString("display_name"),
            role = rs.getString("role"),
            passwordHash = rs.getString("password_hash"),
            disabledAt = rs.instantOrNull("disabled_at"),
        )
    }

    fun setDisabled(c: Connection, id: UUID, disabled: Boolean) {
        c.exec("update users set disabled_at = ? where id = ?", if (disabled) Instant.now() else null, id)
        if (disabled) revokeSessions(c, id)
    }

    fun setPassword(c: Connection, id: UUID, password: String) {
        c.exec("update users set password_hash = ? where id = ?", Passwords.hash(password), id)
    }

    /**
     * Смена пароля обязана выбрасывать чужие сессии: иначе увели токен — и он продолжает
     * работать месяц, сколько пароль ни меняй. [except] оставляет ту, из которой меняли.
     */
    fun revokeSessions(c: Connection, userId: UUID, except: UUID? = null) {
        if (except == null) {
            c.exec("update sessions set revoked_at = now() where user_id = ? and revoked_at is null", userId)
        } else {
            c.exec(
                "update sessions set revoked_at = now() where user_id = ? and id <> ? and revoked_at is null",
                userId, except,
            )
        }
    }

    // --- сессии ---

    fun createSession(c: Connection, userId: UUID, refreshHash: String, device: String?, ttlDays: Long): UUID {
        val id = UUID.randomUUID()
        c.exec(
            """
            insert into sessions (id, user_id, refresh_hash, device, expires_at)
            values (?, ?, ?, ?, now() + make_interval(days => ?))
            """.trimIndent(),
            id, userId, refreshHash, device, ttlDays.toInt(),
        )
        return id
    }

    fun sessionByRefresh(c: Connection, refreshHash: String): Triple<UUID, UUID, String>? = c.selectOne(
        """
        select s.id, s.user_id, u.role
        from sessions s
        join users u on u.id = s.user_id
        where s.refresh_hash = ?
          and s.revoked_at is null
          and s.expires_at > now()
          and u.disabled_at is null
        """.trimIndent(),
        refreshHash,
    ) { rs -> Triple(rs.uuid("id"), rs.uuid("user_id"), rs.getString("role")) }

    fun rotateRefresh(c: Connection, sessionId: UUID, newHash: String, ttlDays: Long) {
        c.exec(
            """
            update sessions
            set refresh_hash = ?, last_seen_at = now(), expires_at = now() + make_interval(days => ?)
            where id = ?
            """.trimIndent(),
            newHash, ttlDays.toInt(), sessionId,
        )
    }

    fun revokeSession(c: Connection, sessionId: UUID) {
        c.exec("update sessions set revoked_at = now() where id = ?", sessionId)
    }

    suspend fun sessionIsActive(sessionId: UUID): Boolean = dbRead { c ->
        c.selectOne(
            """
            select 1 from sessions s join users u on u.id = s.user_id
            where s.id = ? and s.revoked_at is null and s.expires_at > now() and u.disabled_at is null
            """.trimIndent(),
            sessionId,
        ) { it.getInt(1) } != null
    }

    // --- инвайты ---

    fun createInvite(c: Connection, createdBy: UUID, note: String?, ttlDays: Long?): String {
        val code = InviteCodes.generate()
        c.exec(
            """
            insert into invites (code, created_by, note, expires_at)
            values (?, ?, ?, case when ?::int is null then null else now() + make_interval(days => ?::int) end)
            """.trimIndent(),
            code, createdBy, note, ttlDays?.toInt(), ttlDays?.toInt(),
        )
        return code
    }

    fun listInvites(c: Connection): List<InviteRow> = c.select(
        "select code, note, created_at, expires_at, used_by, used_at from invites order by created_at desc limit 200",
    ) { rs ->
        InviteRow(
            code = rs.getString("code"),
            note = rs.getString("note"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.instantOrNull("expires_at"),
            usedBy = rs.getObject("used_by", UUID::class.java),
            usedAt = rs.instantOrNull("used_at"),
        )
    }

    fun deleteInvite(c: Connection, code: String) {
        c.exec("delete from invites where code = ? and used_by is null", code)
    }

    /** Помечает инвайт использованным. Возвращает false, если код не годится. */
    fun consumeInvite(c: Connection, code: String, userId: UUID): Boolean {
        val updated = c.exec(
            """
            update invites set used_by = ?, used_at = now()
            where code = ? and used_by is null and (expires_at is null or expires_at > now())
            """.trimIndent(),
            userId, code.trim().uppercase(Locale.ROOT),
        )
        return updated == 1
    }

    /** Первый администратор: только если в базе вообще нет пользователей. */
    fun bootstrapAdmin(cfg: Config) {
        val email = cfg.bootstrapAdminEmail ?: return
        val password = cfg.bootstrapAdminPassword ?: return
        Db.tx { c ->
            val existing = c.selectOne("select count(*) from users") { it.getLong(1) } ?: 0L
            if (existing > 0L) return@tx
            create(c, email, password, "Администратор", "admin")
            log.warn("Создан первый администратор {} — смените пароль и уберите VM_BOOTSTRAP_* из окружения", email)
        }
    }
}

object InviteCodes {
    // Без похожих символов: 0/O, 1/I/L — коды диктуются голосом.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val random = java.security.SecureRandom()

    fun generate(): String = (1..12)
        .map { ALPHABET[random.nextInt(ALPHABET.length)] }
        .joinToString("")
        .chunked(4)
        .joinToString("-")
}
