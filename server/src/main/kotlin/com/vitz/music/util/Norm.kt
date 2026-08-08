package com.vitz.music.util

import java.text.Normalizer
import java.util.Locale

/**
 * Нормализованная форма названия для поиска и склейки дублей: нижний регистр, без диакритики,
 * без пунктуации, одиночные пробелы. Считается в приложении, а не в SQL, потому что
 * `unaccent()` в Postgres не immutable и по нему нельзя построить индекс без обёрток.
 */
fun normalizeName(raw: String): String {
    val decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
    val sb = StringBuilder(decomposed.length)
    for (ch in decomposed) {
        when {
            // combining diacritical marks
            ch.code in 0x0300..0x036F -> {}
            ch.isLetterOrDigit() -> sb.append(ch)
            ch == 'ё' || ch == 'Ё' -> sb.append('е')
            ch.isWhitespace() -> sb.append(' ')
            else -> sb.append(' ')
        }
    }
    return sb.toString()
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
}

/** «Артист - Название» из имени файла, когда тегов нет. */
fun guessFromFileName(fileName: String): Pair<String?, String> {
    val base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
    val cleaned = base.replace(Regex("^\\s*\\d{1,3}[\\.\\-\\s]+"), "").trim()
    val separators = listOf(" - ", " – ", " — ")
    for (sep in separators) {
        val idx = cleaned.indexOf(sep)
        if (idx > 0) {
            val artist = cleaned.take(idx).trim()
            val title = cleaned.drop(idx + sep.length).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
        }
    }
    return null to (cleaned.ifBlank { fileName })
}
