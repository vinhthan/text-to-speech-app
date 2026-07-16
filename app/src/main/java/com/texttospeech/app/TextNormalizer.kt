package com.texttospeech.app

import java.util.Locale

/**
 * Pre-processes text before TTS so the engine reads it more naturally:
 * - Strips Markdown syntax
 * - Removes URLs
 * - Expands common symbols (&, %, $, …)
 * - Normalises whitespace and special punctuation
 */
object TextNormalizer {

    fun normalize(text: String, locale: Locale): String {
        var t = text
        t = t.replace(Regex("\r\n|\r"), "\n")
        t = stripMarkdown(t)
        t = stripUrls(t)
        t = expandSymbols(t, locale)
        t = fixPunctuation(t)
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex("\\n{4,}"), "\n\n\n")
        return t.trim()
    }

    // ── Markdown stripping ───────────────────────────────────────────────────

    private fun stripMarkdown(text: String): String {
        var t = text
        // Headers
        t = t.replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
        // Bold / italic
        t = t.replace(Regex("""\*{1,3}(.+?)\*{1,3}""", RegexOption.DOT_MATCHES_ALL), "$1")
        t = t.replace(Regex("""_{1,3}(.+?)_{1,3}""",   RegexOption.DOT_MATCHES_ALL), "$1")
        // Strikethrough
        t = t.replace(Regex("""~~(.+?)~~""", RegexOption.DOT_MATCHES_ALL), "$1")
        // Code blocks
        t = t.replace(Regex("""```[\s\S]+?```"""), "")
        t = t.replace(Regex("""`([^`]+)`"""), "$1")
        // Links — keep display text
        t = t.replace(Regex("""!\[.*?\]\(.*?\)"""), "")  // images → remove
        t = t.replace(Regex("""\[(.+?)\]\([^)]*\)"""), "$1")
        // Blockquote / list markers
        t = t.replace(Regex("""^[>*+\-]\s+""", RegexOption.MULTILINE), "")
        t = t.replace(Regex("""^\d+\.\s+""",   RegexOption.MULTILINE), "")
        // Horizontal rule
        t = t.replace(Regex("""^[-_*]{3,}\s*$""", RegexOption.MULTILINE), "")
        return t
    }

    // ── URL removal ──────────────────────────────────────────────────────────

    private fun stripUrls(text: String): String =
        text.replace(Regex("""https?://\S+"""), " ")
            .replace(Regex("""www\.\S+"""), " ")

    // ── Symbol expansion ─────────────────────────────────────────────────────

    private fun expandSymbols(text: String, locale: Locale): String {
        val isVi = locale.language == "vi"
        var t = text

        // Temperature / speed (must come before bare ° / % / $)
        if (isVi) {
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*°C"""))  { "${it.groupValues[1]} độ C" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*°F"""))  { "${it.groupValues[1]} độ F" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*°"""))   { "${it.groupValues[1]} độ" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*km/h""")){ "${it.groupValues[1]} ki-lô-mét trên giờ" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*m/s""")) { "${it.groupValues[1]} mét trên giây" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*%"""))   { "${it.groupValues[1]} phần trăm" }
            t = t.replace(Regex("""\$\s*(\d+(?:[.,]\d+)?)"""))  { "${it.groupValues[1]} đô la" }
            t = t.replace("&", " và ")
            t = t.replace("@", " a còng ")
            t = t.replace("#", " số ")
        } else {
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*°C"""))  { "${it.groupValues[1]} degrees Celsius" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*°F"""))  { "${it.groupValues[1]} degrees Fahrenheit" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*°"""))   { "${it.groupValues[1]} degrees" }
            t = t.replace(Regex("""(\d+(?:[.,]\d+)?)\s*%"""))   { "${it.groupValues[1]} percent" }
            t = t.replace(Regex("""\$\s*(\d+(?:[.,]\d+)?)"""))  { "${it.groupValues[1]} dollars" }
            t = t.replace("&", " and ")
            t = t.replace("@", " at ")
            t = t.replace("#", " number ")
        }

        return t
    }

    // ── Punctuation normalisation ─────────────────────────────────────────────

    private fun fixPunctuation(text: String): String {
        var t = text
        // Smart quotes → plain
        t = t.replace("\u201C", "\"").replace("\u201D", "\"")
        t = t.replace("\u2018", "'").replace("\u2019", "'")
        // Em / en dash → pause-friendly comma
        t = t.replace("\u2014", ", ").replace("\u2013", " - ")
        // Ellipsis
        t = t.replace("\u2026", "... ")
        t = t.replace(Regex("""\.{4,}"""), "... ")
        // Multiple exclamation / question → single
        t = t.replace(Regex("""!{2,}"""), "!")
        t = t.replace(Regex("""?{2,}"""), "?")
        // Bullet characters → newline (natural pause via paragraph break)
        t = t.replace(Regex("""[•·◦▪▸►✓✗]\s*"""), "\n")
        // Remove remaining control / box-drawing chars
        t = t.replace(Regex("""[|\\^~`<>{}【】《》\[\]]"""), " ")
        return t
    }
}
