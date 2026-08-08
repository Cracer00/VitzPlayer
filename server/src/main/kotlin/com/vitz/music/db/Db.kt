package com.vitz.music.db

import com.vitz.music.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection

object Db {
    @Volatile
    private var dataSource: HikariDataSource? = null

    private val ds: HikariDataSource
        get() = dataSource ?: error("Db.init() не вызывался")

    fun init(cfg: Config) {
        val hikari = HikariConfig().apply {
            jdbcUrl = cfg.dbUrl
            username = cfg.dbUser
            password = cfg.dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 10_000
            poolName = "vitz-music"
        }
        dataSource = HikariDataSource(hikari)
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }

    /** Транзакция: коммит на успехе, откат на исключении. */
    fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        val previous = c.autoCommit
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = previous }
        }
    }

    /** Чтение без транзакции. */
    fun <T> read(block: (Connection) -> T): T = ds.connection.use(block)

    fun healthy(): Boolean = runCatching {
        read { c -> c.selectOne("select 1") { it.getInt(1) } == 1 }
    }.getOrDefault(false)
}

/** JDBC блокирующий, поэтому из корутин ходим в базу только через пул IO. */
suspend fun <T> dbTx(block: (Connection) -> T): T = withContext(Dispatchers.IO) { Db.tx(block) }

suspend fun <T> dbRead(block: (Connection) -> T): T = withContext(Dispatchers.IO) { Db.read(block) }
