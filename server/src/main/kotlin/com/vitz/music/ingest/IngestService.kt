package com.vitz.music.ingest

import com.vitz.music.Config
import com.vitz.music.catalog.Catalog
import com.vitz.music.db.Db
import com.vitz.music.media.COVER_SIZES
import com.vitz.music.media.MediaStore
import com.vitz.music.util.guessFromFileName
import com.vitz.music.util.normalizeName
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID

/**
 * Конвейер ингеста — единственный путь, которым трек попадает в каталог: и ручная загрузка,
 * и будущий импортёр приводят файл во временный каталог и зовут сюда.
 *
 * Порядок шагов важен: всё, что читает исходный файл, происходит до того, как файл переедет
 * в хранилище, иначе пришлось бы гоняться за путями.
 */
class IngestService(
    private val store: MediaStore,
    private val ffmpeg: Ffmpeg,
    private val cfg: Config,
) {
    private val log = LoggerFactory.getLogger(IngestService::class.java)

    data class Overrides(
        val artist: String? = null,
        val title: String? = null,
        val album: String? = null,
        val year: Int? = null,
        val genre: String? = null,
        val trackNo: Int? = null,
    )

    sealed interface Result {
        data class Created(val trackId: UUID, val artist: String, val title: String) : Result
        data class Duplicate(val trackId: UUID) : Result
        data class NearDuplicate(val candidates: List<Catalog.DuplicateCandidate>) : Result
    }

    @Suppress("LongParameterList", "LongMethod")
    fun ingest(
        temp: Path,
        originalName: String,
        addedBy: UUID?,
        overrides: Overrides = Overrides(),
        source: String = "upload",
        sourceUrl: String? = null,
        force: Boolean = false,
        log: (String) -> Unit = {},
    ): Result {
        require(Files.isRegularFile(temp)) { "Файл $temp пропал до начала разбора" }

        val audioSha = MediaStore.sha256(temp)
        log("sha256 $audioSha")

        Db.read { c -> Catalog.findBySha(c, audioSha) }?.let { existing ->
            log("Точный дубль уже в каталоге: $existing")
            Files.deleteIfExists(temp)
            return Result.Duplicate(existing)
        }

        val probe = ffmpeg.probe(temp)
        log("Формат ${probe.formatName}/${probe.codec}, ${probe.durationMs / 1000} с, ${probe.bitrate ?: "?"} kbps")
        if (probe.durationMs <= 0) throw FfmpegException("Не удалось определить длительность")

        val tags = Tags.read(temp.toFile())
        val guessed = guessFromFileName(originalName)

        val artistName = firstNonBlank(
            overrides.artist, tags.artist, tags.albumArtist, probe.tags["artist"], guessed.first,
        ) ?: "Неизвестный исполнитель"
        val title = firstNonBlank(
            overrides.title, tags.title, probe.tags["title"], guessed.second,
        ) ?: originalName.substringBeforeLast('.')
        val albumTitle = firstNonBlank(overrides.album, tags.album, probe.tags["album"])
        val year = overrides.year ?: tags.year ?: probe.tags["date"]?.take(4)?.toIntOrNull()
        val genre = firstNonBlank(overrides.genre, tags.genre, probe.tags["genre"])
        val trackNo = overrides.trackNo ?: tags.trackNo ?: probe.tags["track"]?.substringBefore('/')?.toIntOrNull()

        log("Определено: $artistName — $title")

        if (!force) {
            val near = Db.read { c ->
                Catalog.findNearDuplicates(c, normalizeName(artistName), normalizeName(title), probe.durationMs)
            }
            if (near.isNotEmpty()) {
                log("Похоже на дубль: ${near.joinToString { "${it.artist} — ${it.title}" }}")
                return Result.NearDuplicate(near)
            }
        }

        val coverSha = makeCover(temp, tags.artwork, probe.hasCoverStream, log)

        val loudness = ffmpeg.measureLoudness(temp)
        val gainDb = loudness?.gainToTarget()
        if (gainDb != null) log("Громкость ${"%.1f".format(loudness.inputI)} LUFS → сдвиг ${"%.1f".format(gainDb)} dB")

        val opusTemp = store.temp(".opus")
        ffmpeg.encodeOpus(temp, opusTemp, gainDb)
        val opusSha = MediaStore.sha256(opusTemp)
        val opusSize = Files.size(opusTemp)
        store.put(opusTemp, "rendition", opusSha, "opus")
        log("Рендиция opus-96: ${opusSize / 1024} КБ")

        val originalExt = originalName.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotBlank() && it.length <= 5 }
            ?: extForFormat(probe.formatName)
        val originalSize = Files.size(temp)
        val stored = store.put(temp, "audio", audioSha, originalExt)

        return try {
            Db.tx { c ->
                val coverId = coverSha?.let { Catalog.upsertCover(c, it, COVER_SIZES.max(), COVER_SIZES.max()) }
                val artistId = Catalog.upsertArtist(c, artistName)
                val albumId = albumTitle?.let { Catalog.upsertAlbum(c, artistId, it, year, coverId) }
                val trackId = Catalog.insertTrack(
                    c = c,
                    artistId = artistId,
                    albumId = albumId,
                    title = title,
                    trackNo = trackNo,
                    discNo = tags.discNo,
                    durationMs = probe.durationMs,
                    year = year,
                    genre = genre,
                    coverId = coverId,
                    audioSha = audioSha,
                    sizeBytes = originalSize,
                    format = originalExt,
                    bitrate = probe.bitrate,
                    sampleRate = probe.sampleRate,
                    channels = probe.channels,
                    gainDb = gainDb,
                    source = source,
                    sourceUrl = sourceUrl,
                    addedBy = addedBy,
                )
                Catalog.insertRendition(c, trackId, "original", audioSha, originalExt, originalSize, probe.bitrate)
                Catalog.insertRendition(c, trackId, "opus-96", opusSha, "opus", opusSize, 96)
                Result.Created(trackId, artistName, title)
            }
        } catch (e: SQLException) {
            // Гонка двух одинаковых загрузок: уникальный индекс по audio_sha256 отработал.
            val existing = Db.read { c -> Catalog.findBySha(c, audioSha) }
            if (existing != null) {
                Result.Duplicate(existing)
            } else {
                returnToIncoming(stored, temp)
                throw e
            }
        } catch (e: Exception) {
            returnToIncoming(stored, temp)
            throw e
        }
    }

    /**
     * Оригинал переезжает в хранилище до записи в базу. Если запись не удалась, файл нужно
     * вернуть на место — иначе повтор задания упрётся в «временный файл не найден»,
     * а трек так и не появится в каталоге.
     */
    private fun returnToIncoming(stored: Path, temp: Path) {
        runCatching {
            if (Files.exists(stored) && !Files.exists(temp)) Files.move(stored, temp)
        }.onFailure { log.warn("Не удалось вернуть файл во временный каталог: {}", it.message) }
    }

    /** Возвращает sha исходной картинки; варианты кладутся рядом как `<sha>.<size>.webp`. */
    private fun makeCover(source: Path, artwork: ByteArray?, hasCoverStream: Boolean, log: (String) -> Unit): String? {
        val raw: Path = when {
            artwork != null && artwork.isNotEmpty() -> store.temp(".img").also { Files.write(it, artwork) }
            hasCoverStream -> store.temp(".img").let { candidate ->
                if (ffmpeg.extractCover(source, candidate)) candidate else {
                    Files.deleteIfExists(candidate)
                    return null
                }
            }
            else -> return null
        }

        return try {
            val sha = MediaStore.sha256(raw)
            if (COVER_SIZES.all { store.exists("cover", sha, "$it.webp") }) {
                log("Обложка уже есть в хранилище")
                return sha
            }
            for (size in COVER_SIZES) {
                val variant = store.temp(".webp")
                ffmpeg.coverVariant(raw, variant, size)
                store.put(variant, "cover", sha, "$size.webp")
            }
            log("Обложка сохранена в ${COVER_SIZES.joinToString()} px")
            sha
        } catch (e: Exception) {
            this.log.warn("Обложку сделать не удалось: {}", e.message)
            null
        } finally {
            Files.deleteIfExists(raw)
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun extForFormat(formatName: String): String = when (formatName.lowercase()) {
        "mp3", "mp2", "mp1" -> "mp3"
        "flac" -> "flac"
        "ogg" -> "ogg"
        "wav" -> "wav"
        "mov", "mp4", "m4a", "3gp" -> "m4a"
        "matroska", "webm" -> "webm"
        else -> "bin"
    }

    val uploadMaxBytes: Long get() = cfg.uploadMaxBytes
}
