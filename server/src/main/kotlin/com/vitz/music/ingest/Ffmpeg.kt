package com.vitz.music.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class FfmpegException(message: String) : RuntimeException(message)

/**
 * Обёртка над ffmpeg/ffprobe. Всё синхронное и блокирующее — вызывается из воркера,
 * который и так живёт на пуле IO.
 */
class Ffmpeg(private val ffmpegBin: String, private val ffprobeBin: String) {
    private val log = LoggerFactory.getLogger(Ffmpeg::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Probe(
        val durationMs: Long,
        val formatName: String,
        val codec: String?,
        val bitrate: Int?,
        val sampleRate: Int?,
        val channels: Int?,
        val hasCoverStream: Boolean,
        val tags: Map<String, String>,
    )

    data class Loudness(val inputI: Double, val inputTp: Double, val inputLra: Double, val inputThresh: Double) {
        /**
         * Сдвиг до цели −14 LUFS, но не настолько, чтобы загнать пики выше −1 dBTP:
         * клиппинг в машине слышно куда лучше, чем разницу в пару децибел.
         */
        fun gainToTarget(targetLufs: Double = -14.0, ceilingTp: Double = -1.0): Double {
            val raw = targetLufs - inputI
            val headroom = ceilingTp - inputTp
            return minOf(raw, headroom).coerceIn(-20.0, 20.0)
        }
    }

    fun available(): Boolean = runCatching { run(listOf(ffmpegBin, "-version"), timeoutSeconds = 10).exitCode == 0 }
        .getOrDefault(false)

    fun probe(file: Path): Probe {
        val result = run(
            listOf(
                ffprobeBin, "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", file.toString(),
            ),
            timeoutSeconds = 60,
        )
        if (result.exitCode != 0) throw FfmpegException("ffprobe вернул ${result.exitCode}: ${result.stderr.take(500)}")

        val root = json.parseToJsonElement(result.stdout).jsonObject
        val format = root["format"]?.jsonObject
        val streams = root["streams"]?.jsonArray.orEmpty().map { it.jsonObject }
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" }
            ?: throw FfmpegException("В файле нет аудиодорожки")
        val video = streams.firstOrNull { it.str("codec_type") == "video" }

        val durationSec = audio.str("duration")?.toDoubleOrNull() ?: format?.str("duration")?.toDoubleOrNull()
        val tags = buildMap {
            format?.get("tags")?.jsonObject?.forEach { (k, v) ->
                put(k.lowercase(), v.jsonPrimitive.content)
            }
            audio["tags"]?.jsonObject?.forEach { (k, v) ->
                putIfAbsent(k.lowercase(), v.jsonPrimitive.content)
            }
        }

        return Probe(
            durationMs = ((durationSec ?: 0.0) * 1000).toLong(),
            formatName = format?.str("format_name")?.split(',')?.first() ?: "unknown",
            codec = audio.str("codec_name"),
            bitrate = (format?.str("bit_rate") ?: audio.str("bit_rate"))?.toIntOrNull()?.div(1000),
            sampleRate = audio.str("sample_rate")?.toIntOrNull(),
            channels = audio["channels"]?.jsonPrimitive?.content?.toIntOrNull(),
            hasCoverStream = video != null,
            tags = tags,
        )
    }

    /** Двухпроходный loudnorm: первый проход только измеряет и печатает JSON в stderr. */
    fun measureLoudness(file: Path): Loudness? {
        val result = run(
            listOf(
                ffmpegBin, "-hide_banner", "-nostats", "-i", file.toString(),
                "-af", "loudnorm=I=-14:TP=-1:LRA=11:print_format=json",
                "-f", "null", "-",
            ),
            timeoutSeconds = 600,
        )
        val text = result.stderr
        val start = text.lastIndexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            log.warn("loudnorm не выдал JSON для {}", file.fileName)
            return null
        }
        return runCatching {
            val obj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            Loudness(
                inputI = obj.str("input_i")?.toDouble() ?: return null,
                inputTp = obj.str("input_tp")?.toDouble() ?: return null,
                inputLra = obj.str("input_lra")?.toDouble() ?: 0.0,
                inputThresh = obj.str("input_thresh")?.toDouble() ?: 0.0,
            )
        }.getOrNull()
    }

    fun encodeOpus(src: Path, dst: Path, gainDb: Double?, bitrateKbps: Int = 96) {
        val filters = buildList {
            if (gainDb != null && kotlin.math.abs(gainDb) > 0.1) add("volume=${"%.2f".format(java.util.Locale.ROOT, gainDb)}dB")
        }
        val cmd = buildList {
            addAll(listOf(ffmpegBin, "-hide_banner", "-nostats", "-y", "-i", src.toString()))
            if (filters.isNotEmpty()) addAll(listOf("-af", filters.joinToString(",")))
            addAll(
                listOf(
                    "-vn", "-map_metadata", "0",
                    "-c:a", "libopus", "-b:a", "${bitrateKbps}k", "-vbr", "on", "-application", "audio",
                    dst.toString(),
                ),
            )
        }
        val result = run(cmd, timeoutSeconds = 900)
        if (result.exitCode != 0) throw FfmpegException("Кодирование opus не удалось: ${result.stderr.takeLast(500)}")
    }

    /** Достаёт встроенную обложку. false — её просто нет, это не ошибка. */
    fun extractCover(src: Path, dst: Path): Boolean {
        val result = run(
            listOf(ffmpegBin, "-hide_banner", "-nostats", "-y", "-i", src.toString(), "-an", "-map", "0:v:0", "-c:v", "copy", dst.toString()),
            timeoutSeconds = 120,
        )
        return result.exitCode == 0 && dst.toFile().length() > 0
    }

    /** Квадратный вариант обложки нужного размера. */
    fun coverVariant(src: Path, dst: Path, size: Int) {
        val result = run(
            listOf(
                ffmpegBin, "-hide_banner", "-nostats", "-y", "-i", src.toString(),
                "-vf", "scale=$size:$size:force_original_aspect_ratio=increase,crop=$size:$size",
                "-frames:v", "1", "-c:v", "libwebp", "-quality", "82",
                dst.toString(),
            ),
            timeoutSeconds = 120,
        )
        if (result.exitCode != 0) throw FfmpegException("Не удалось сделать обложку $size: ${result.stderr.takeLast(300)}")
    }

    // --- запуск процесса ---

    private data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(command: List<String>, timeoutSeconds: Long): RunResult {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.outputStream.close()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = drain(process.inputStream, stdout)
        val errThread = drain(process.errorStream, stderr)

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(1000)
            errThread.join(1000)
            throw FfmpegException("Процесс ${command.first()} не уложился в $timeoutSeconds с")
        }
        outThread.join(5000)
        errThread.join(5000)
        return RunResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(into) {
                        // Ограничиваем: у ffmpeg бывает очень многословный stderr.
                        if (into.length < 200_000) into.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true; start() }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content
