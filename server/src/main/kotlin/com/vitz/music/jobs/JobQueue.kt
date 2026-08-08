package com.vitz.music.jobs

import com.vitz.music.db.Db
import com.vitz.music.db.exec
import com.vitz.music.db.instant
import com.vitz.music.db.select
import com.vitz.music.db.selectOne
import com.vitz.music.db.uuid
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

const val JOB_INGEST = "ingest"

data class JobRow(
    val id: UUID,
    val kind: String,
    val payload: String,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val error: String?,
    val log: String,
    val result: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Очередь заданий прямо в Postgres. `for update skip locked` даёт корректную выдачу нескольким
 * воркерам без Redis — при двух процессах и десятках заданий в минуту этого хватает с запасом.
 */
class JobQueue {

    fun enqueue(c: Connection, kind: String, payloadJson: String): UUID {
        val id = UUID.randomUUID()
        c.exec("insert into jobs (id, kind, payload) values (?, ?, ?::jsonb)", id, kind, payloadJson)
        return id
    }

    fun enqueue(kind: String, payloadJson: String): UUID = Db.tx { c -> enqueue(c, kind, payloadJson) }

    fun claim(workerId: String): JobRow? = Db.tx { c ->
        c.selectOne(
            """
            update jobs
            set status = 'running', attempts = attempts + 1, locked_by = ?, locked_at = now(), updated_at = now()
            where id = (
                select id from jobs
                where status = 'queued' and run_after <= now()
                order by created_at
                for update skip locked
                limit 1
            )
            returning id, kind, payload::text as payload, status, attempts, max_attempts, error, log,
                      result::text as result, created_at, updated_at
            """.trimIndent(),
            workerId,
        ) { map(it) }
    }

    fun complete(id: UUID, resultJson: String?) = Db.tx { c ->
        c.exec(
            "update jobs set status = 'done', result = ?::jsonb, error = null, locked_by = null, updated_at = now() where id = ?",
            resultJson, id,
        )
        Unit
    }

    fun needsAttention(id: UUID, resultJson: String?, reason: String) = Db.tx { c ->
        c.exec(
            """
            update jobs set status = 'needs_attention', result = ?::jsonb, error = ?, locked_by = null, updated_at = now()
            where id = ?
            """.trimIndent(),
            resultJson, reason, id,
        )
        Unit
    }

    /** Повтор с откатом: 30 с, 2 мин, 8 мин. Дальше — в «упало», разбираться руками. */
    fun fail(id: UUID, error: String) = Db.tx { c ->
        c.exec(
            """
            update jobs
            set status = case when attempts >= max_attempts then 'failed' else 'queued' end,
                run_after = now() + make_interval(secs => least(30 * power(4, attempts - 1), 3600)::int),
                error = ?, locked_by = null, updated_at = now()
            where id = ?
            """.trimIndent(),
            error.take(4000), id,
        )
        Unit
    }

    fun appendLog(id: UUID, line: String) = Db.tx { c ->
        c.exec(
            "update jobs set log = left(log || ? || E'\\n', 60000), updated_at = now() where id = ?",
            line.take(1000), id,
        )
        Unit
    }

    fun retry(c: Connection, id: UUID) {
        c.exec(
            "update jobs set status = 'queued', attempts = 0, run_after = now(), error = null, updated_at = now() where id = ?",
            id,
        )
    }

    fun byId(c: Connection, id: UUID): JobRow? = c.selectOne(
        """
        select id, kind, payload::text as payload, status, attempts, max_attempts, error, log,
               result::text as result, created_at, updated_at
        from jobs where id = ?
        """.trimIndent(),
        id,
    ) { map(it) }

    fun recent(c: Connection, limit: Int = 50): List<JobRow> = c.select(
        """
        select id, kind, payload::text as payload, status, attempts, max_attempts, error, log,
               result::text as result, created_at, updated_at
        from jobs order by created_at desc limit ?
        """.trimIndent(),
        limit,
    ) { map(it) }

    fun counts(c: Connection): Map<String, Long> = c.select(
        "select status, count(*) as n from jobs group by status",
    ) { rs -> rs.getString("status") to rs.getLong("n") }.toMap()

    /** Возвращает зависшие задания в очередь: воркер мог умереть вместе с контейнером. */
    fun requeueStale(staleMinutes: Int = 30): Int = Db.tx { c ->
        c.exec(
            """
            update jobs set status = 'queued', locked_by = null, updated_at = now()
            where status = 'running' and locked_at < now() - make_interval(mins => ?)
            """.trimIndent(),
            staleMinutes,
        )
    }

    private fun map(rs: ResultSet) = JobRow(
        id = rs.uuid("id"),
        kind = rs.getString("kind"),
        payload = rs.getString("payload"),
        status = rs.getString("status"),
        attempts = rs.getInt("attempts"),
        maxAttempts = rs.getInt("max_attempts"),
        error = rs.getString("error"),
        log = rs.getString("log") ?: "",
        result = rs.getString("result"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
    )
}
