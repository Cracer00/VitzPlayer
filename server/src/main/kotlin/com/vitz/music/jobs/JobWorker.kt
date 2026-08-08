package com.vitz.music.jobs

import com.vitz.music.AppServices
import com.vitz.music.ingest.IngestService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Serializable
data class IngestPayload(
    val temp: String,
    val name: String,
    val addedBy: String? = null,
    val source: String = "upload",
    val sourceUrl: String? = null,
    val force: Boolean = false,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackNo: Int? = null,
)

@Serializable
data class IngestResultJson(
    val status: String,
    val trackId: String? = null,
    val artist: String? = null,
    val title: String? = null,
    val candidates: List<String> = emptyList(),
)

class JobWorker(private val services: AppServices, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(JobWorker::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val queue = services.queue

    fun start() {
        val workers = services.cfg.workerConcurrency.coerceIn(1, 8)
        log.info("Воркер заданий: {} поток(а/ов)", workers)
        repeat(workers) { index ->
            scope.launch(Dispatchers.IO) { loop("worker-$index") }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { queue.requeueStale() }
                    .onSuccess { if (it > 0) log.warn("Вернул в очередь зависших заданий: {}", it) }
                delay(5 * 60 * 1000)
            }
        }
    }

    private suspend fun loop(workerId: String) {
        while (true) {
            val job = runCatching { queue.claim(workerId) }
                .onFailure { log.error("Не удалось взять задание", it) }
                .getOrNull()
            if (job == null) {
                delay(1000)
                continue
            }
            runCatching { handle(job) }
                .onFailure { e ->
                    log.error("Задание {} ({}) упало", job.id, job.kind, e)
                    queue.fail(job.id, e.message ?: e::class.java.simpleName)
                }
        }
    }

    private suspend fun handle(job: JobRow) = withContext(Dispatchers.IO) {
        when (job.kind) {
            JOB_INGEST -> handleIngest(job)
            else -> queue.fail(job.id, "Неизвестный тип задания ${job.kind}")
        }
    }

    private fun handleIngest(job: JobRow) {
        val payload = json.decodeFromString<IngestPayload>(job.payload)
        val temp: Path = Paths.get(payload.temp)
        if (!Files.isRegularFile(temp)) {
            queue.fail(job.id, "Временный файл ${payload.temp} не найден")
            return
        }

        val logLine: (String) -> Unit = { line -> queue.appendLog(job.id, line) }
        logLine("Начинаю разбор ${payload.name}")

        val result = services.ingest.ingest(
            temp = temp,
            originalName = payload.name,
            addedBy = payload.addedBy?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            overrides = IngestService.Overrides(
                artist = payload.artist,
                title = payload.title,
                album = payload.album,
                year = payload.year,
                genre = payload.genre,
                trackNo = payload.trackNo,
            ),
            source = payload.source,
            sourceUrl = payload.sourceUrl,
            force = payload.force,
            log = logLine,
        )

        when (result) {
            is IngestService.Result.Created -> {
                logLine("Готово: ${result.artist} — ${result.title}")
                queue.complete(
                    job.id,
                    json.encodeToString(
                        IngestResultJson(
                            status = "created",
                            trackId = result.trackId.toString(),
                            artist = result.artist,
                            title = result.title,
                        ),
                    ),
                )
            }

            is IngestService.Result.Duplicate -> {
                logLine("Такой файл уже есть")
                queue.complete(
                    job.id,
                    json.encodeToString(IngestResultJson(status = "duplicate", trackId = result.trackId.toString())),
                )
            }

            is IngestService.Result.NearDuplicate -> {
                // Файл намеренно остаётся на диске: админ может подтвердить загрузку кнопкой.
                queue.needsAttention(
                    job.id,
                    json.encodeToString(
                        IngestResultJson(
                            status = "near_duplicate",
                            candidates = result.candidates.map { "${it.artist} — ${it.title}" },
                        ),
                    ),
                    "Похоже на дубль уже имеющегося трека",
                )
            }
        }
    }
}
