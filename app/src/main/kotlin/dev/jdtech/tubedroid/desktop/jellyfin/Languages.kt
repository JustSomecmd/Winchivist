package dev.jdtech.tubedroid.desktop.jellyfin

import java.util.Locale

/**
 * A language to prefer for sound or subtitles: its three-letter code, its two-letter one and its
 * name in English.
 */
class Language(val code: String, val short: String, val name: String)

/**
 * The languages a sound or subtitle track can be in. Files name theirs in more than one way ("nl",
 * "nld" and "dut" are all Dutch), so every way is read as the one code a [Language] has.
 */
object Languages {
    /** Every language Java knows by a two-letter code, by name. */
    val all: List<Language> by lazy {
        Locale.getISOLanguages()
            .mapNotNull { short ->
                val locale = Locale.forLanguageTag(short)
                val code = runCatching { locale.getISO3Language() }.getOrNull()
                val name = locale.getDisplayLanguage(Locale.ENGLISH)
                // One without a name of its own shows as its code
                if (code?.length != 3 || name.isBlank() || name.equals(short, ignoreCase = true)) {
                    null
                } else {
                    Language(code, short, name)
                }
            }
            .distinctBy { it.code }
            .sortedBy { it.name }
    }

    fun named(code: String?): Language? = code?.let { wanted ->
        all.firstOrNull { it.code == wanted }
    }

    /** The code of a track's language as a [Language] has it, or null when it names none. */
    fun code(tag: String?): String? {
        val base = tag?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        return when (base?.length) {
            2 -> all.firstOrNull { it.short == base }?.code
            3 -> OTHER_CODES[base] ?: base
            else -> null
        }
    }

    /** A language as mpv's alang and slang options take it: each way files write it, in a list. */
    fun forMpv(language: String?): String? {
        val wanted = code(language) ?: return null
        val other = OTHER_CODES.entries.firstOrNull { it.value == wanted }?.key
        return listOfNotNull(wanted, other, named(wanted)?.short).joinToString(",")
    }

    // The 20 languages with a second three-letter code (ISO 639-2's B codes, which Matroska files
    // use), each with the code Java gives
    private val OTHER_CODES =
        mapOf(
            "alb" to "sqi",
            "arm" to "hye",
            "baq" to "eus",
            "bur" to "mya",
            "chi" to "zho",
            "cze" to "ces",
            "dut" to "nld",
            "fre" to "fra",
            "geo" to "kat",
            "ger" to "deu",
            "gre" to "ell",
            "ice" to "isl",
            "mac" to "mkd",
            "mao" to "mri",
            "may" to "msa",
            "per" to "fas",
            "rum" to "ron",
            "slo" to "slk",
            "tib" to "bod",
            "wel" to "cym",
        )
}
