package com.vitz.music.db

import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * Тонкая обёртка над JDBC. Осознанно без ORM: схема здесь маленькая, а запросы
 * (триграммный поиск, `for update skip locked`, оконные функции) всё равно пишутся руками.
 */

private fun PreparedStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { index, value ->
        val i = index + 1
        when (value) {
            null -> setNull(i, Types.NULL)
            is String -> setString(i, value)
            is UUID -> setObject(i, value)
            is Int -> setInt(i, value)
            is Long -> setLong(i, value)
            is Short -> setShort(i, value)
            is Boolean -> setBoolean(i, value)
            is Double -> setDouble(i, value)
            is Float -> setFloat(i, value)
            is BigDecimal -> setBigDecimal(i, value)
            is ByteArray -> setBytes(i, value)
            is Instant -> setTimestamp(i, Timestamp.from(value))
            is Enum<*> -> setString(i, value.name)
            else -> setObject(i, value)
        }
    }
}

fun Connection.exec(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { st ->
        st.bindAll(args)
        st.executeUpdate()
    }

fun <T> Connection.select(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bindAll(args)
        st.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out.add(map(rs))
            out
        }
    }

fun <T> Connection.selectOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { st ->
        st.bindAll(args)
        st.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

fun Connection.count(sql: String, vararg args: Any?): Long =
    selectOne(sql, *args) { it.getLong(1) } ?: 0L

// --- чтение колонок с корректным null ---

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

fun ResultSet.intOrNull(column: String): Int? = getInt(column).let { if (wasNull()) null else it }

fun ResultSet.longOrNull(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).let { if (wasNull()) null else it }
