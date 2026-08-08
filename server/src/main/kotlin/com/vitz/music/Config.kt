package com.vitz.music

import java.nio.file.Path
import java.nio.file.Paths

/** Небезопасные значения по умолчанию: годятся только для localhost, наружу с ними не выпускаем. */
private const val DEV_SECRET = "dev-insecure-secret-change-me-please-0000"

data class Config(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val signSecret: String,
    val mediaRoot: Path,
    val publicUrl: String,
    val accessTtlMinutes: Long,
    val refreshTtlDays: Long,
    val signTtlSeconds: Long,
    val uploadMaxBytes: Long,
    val workerConcurrency: Int,
    val ffmpeg: String,
    val ffprobe: String,
    val bootstrapAdminEmail: String?,
    val bootstrapAdminPassword: String?,
) {
    val isLocal: Boolean get() = publicUrl.contains("localhost") || publicUrl.contains("127.0.0.1")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun str(key: String, default: String? = null): String =
                env[key]?.takeIf { it.isNotBlank() }
                    ?: default
                    ?: error("Не задана обязательная переменная окружения $key")

            fun num(key: String, default: Long): Long = env[key]?.trim()?.toLongOrNull() ?: default

            val cfg = Config(
                port = num("VM_PORT", 8080).toInt(),
                dbUrl = str("VM_DB_URL", "jdbc:postgresql://localhost:5432/vitzmusic"),
                dbUser = str("VM_DB_USER", "vitzmusic"),
                dbPassword = str("VM_DB_PASSWORD", "vitzmusic"),
                jwtSecret = str("VM_JWT_SECRET", DEV_SECRET),
                signSecret = str("VM_MEDIA_SIGN_SECRET", DEV_SECRET),
                mediaRoot = Paths.get(str("VM_MEDIA_ROOT", "media")).toAbsolutePath().normalize(),
                publicUrl = str("VM_PUBLIC_URL", "http://localhost:8080").trimEnd('/'),
                accessTtlMinutes = num("VM_ACCESS_TTL_MINUTES", 15),
                refreshTtlDays = num("VM_REFRESH_TTL_DAYS", 30),
                signTtlSeconds = num("VM_SIGN_TTL_SECONDS", 6 * 3600),
                uploadMaxBytes = num("VM_UPLOAD_MAX_BYTES", 200L * 1024 * 1024),
                workerConcurrency = num("VM_WORKER_CONCURRENCY", 2).toInt(),
                ffmpeg = str("VM_FFMPEG", "ffmpeg"),
                ffprobe = str("VM_FFPROBE", "ffprobe"),
                bootstrapAdminEmail = env["VM_BOOTSTRAP_ADMIN_EMAIL"]?.takeIf { it.isNotBlank() },
                bootstrapAdminPassword = env["VM_BOOTSTRAP_ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
            )
            cfg.validate()
            return cfg
        }
    }

    fun validate() {
        if (!isLocal) {
            require(jwtSecret != DEV_SECRET) { "VM_JWT_SECRET остался дефолтным — так наружу нельзя" }
            require(signSecret != DEV_SECRET) { "VM_MEDIA_SIGN_SECRET остался дефолтным — так наружу нельзя" }
            require(publicUrl.startsWith("https://")) { "VM_PUBLIC_URL должен быть https" }
        }
        require(jwtSecret.length >= 32) { "VM_JWT_SECRET короче 32 символов" }
        require(signSecret.length >= 32) { "VM_MEDIA_SIGN_SECRET короче 32 символов" }
        require(jwtSecret != signSecret || isLocal) { "Секреты JWT и подписи ссылок должны различаться" }
    }
}
