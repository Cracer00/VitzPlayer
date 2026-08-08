package com.vitz.music.ingest

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Чтение тегов. JAudiotagger знает больше форматов, чем ffprobe, и аккуратнее с ID3v2,
 * но охотно кидает исключения на битых файлах — поэтому здесь всё в runCatching,
 * а недостающее добирается из ffprobe и из имени файла.
 */
object Tags {
    init {
        // Библиотека пишет в java.util.logging и очень многословна.
        Logger.getLogger("org.jaudiotagger").level = Level.SEVERE
    }

    data class Meta(
        val artist: String? = null,
        val albumArtist: String? = null,
        val title: String? = null,
        val album: String? = null,
        val year: Int? = null,
        val genre: String? = null,
        val trackNo: Int? = null,
        val discNo: Int? = null,
        val artwork: ByteArray? = null,
    )

    fun read(file: File): Meta = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag ?: return Meta()

        fun field(key: FieldKey): String? = runCatching { tag.getFirst(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        Meta(
            artist = field(FieldKey.ARTIST),
            albumArtist = field(FieldKey.ALBUM_ARTIST),
            title = field(FieldKey.TITLE),
            album = field(FieldKey.ALBUM),
            year = field(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
            genre = field(FieldKey.GENRE),
            trackNo = field(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
            discNo = field(FieldKey.DISC_NO)?.substringBefore('/')?.toIntOrNull(),
            artwork = runCatching { tag.firstArtwork?.binaryData }.getOrNull(),
        )
    }.getOrElse { Meta() }
}
