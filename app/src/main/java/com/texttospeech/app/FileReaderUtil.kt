package com.texttospeech.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

object FileReaderUtil {

    // ─── Public API ─────────────────────────────────────────────────────────

    fun readFile(context: Context, uri: Uri): Result<String> = try {
        val mime = context.contentResolver.getType(uri) ?: ""
        val name = getFileName(context, uri).lowercase()

        when {
            mime.contains("pdf") || name.endsWith(".pdf")               -> readPdf(context, uri)
            mime.contains("wordprocessingml") || name.endsWith(".docx") -> readDocx(context, uri)
            name.endsWith(".odt")                                        -> readOdt(context, uri)
            name.endsWith(".rtf")                                        -> readRtf(context, uri)
            else                                                         -> readPlainText(context, uri)
        }
    } catch (e: Exception) {
        Result.failure(Exception("Không thể đọc file: ${e.message}"))
    }

    fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx) ?: ""
            }
        }
        return name.ifBlank { uri.lastPathSegment ?: "Không rõ tên" }
    }

    // ─── Plain text (.txt, .md, .csv …) ────────────────────────────────────

    private fun readPlainText(context: Context, uri: Uri): Result<String> = try {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line -> sb.appendLine(line) }
            }
        }
        Result.success(sb.toString())
    } catch (e: Exception) {
        Result.failure(Exception("Lỗi đọc file văn bản: ${e.message}"))
    }

    // ─── PDF (built-in parser — no external library) ────────────────────────
    //
    // Works well for text-based PDFs. Scanned/image-only PDFs return empty text.
    // Handles both uncompressed and FlateDecode (zlib) compressed content streams.

    private fun readPdf(context: Context, uri: Uri): Result<String> {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                ?: return Result.success("")
            val text = extractPdfText(bytes)
            if (text.isBlank()) {
                Result.failure(Exception(
                    "Không thể trích xuất văn bản từ PDF này.\n" +
                    "Thử mở trong Google Docs → Copy → Paste vào ô văn bản."
                ))
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Lỗi đọc PDF: ${e.message}"))
        }
    }

    private fun extractPdfText(data: ByteArray): String {
        val result = StringBuilder()
        // Read as ISO-8859-1 to preserve byte values
        val raw = String(data, Charsets.ISO_8859_1)

        // Find all object streams and content streams
        var searchFrom = 0
        while (true) {
            val streamIdx = raw.indexOf("stream", searchFrom)
            if (streamIdx == -1) break

            // Find content start (after "stream" + CR? + LF)
            var contentStart = streamIdx + 6
            if (contentStart < raw.length && raw[contentStart] == '\r') contentStart++
            if (contentStart < raw.length && raw[contentStart] == '\n') contentStart++

            val endIdx = raw.indexOf("endstream", contentStart)
            if (endIdx == -1) break

            // Look back for the stream dictionary to find the filter
            val dictWindow = raw.substring(maxOf(0, streamIdx - 600), streamIdx)
            val isFlate = dictWindow.contains("/FlateDecode") || dictWindow.contains("/Fl ")
            val isImage = dictWindow.contains("/Subtype /Image") || dictWindow.contains("/Subtype/Image")

            if (!isImage) {
                val rawBytes = data.copyOfRange(contentStart, minOf(endIdx, data.size))
                val streamText = if (isFlate) {
                    decompressFlate(rawBytes)
                } else {
                    String(rawBytes, Charsets.ISO_8859_1)
                }
                if (streamText != null) {
                    extractTextOps(streamText, result)
                }
            }

            searchFrom = endIdx + 9
        }

        return result.toString()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Decompress a FlateDecode (zlib/deflate) stream. Returns null on failure. */
    private fun decompressFlate(compressed: ByteArray): String? = try {
        val out = ByteArrayOutputStream()
        InflaterInputStream(compressed.inputStream()).use { it.copyTo(out) }
        out.toString(Charsets.ISO_8859_1.name())
    } catch (_: Exception) { null }

    /**
     * Extract human-readable text from a PDF content stream.
     *
     * Operators handled (in document order via a single combined regex):
     *   (text)Tj  — show string
     *   [(a)(b)]TJ — kerned/array text
     *   T* / Td / TD — move to next visual line → flush + newline
     *
     * Processing in document order is critical: the old code ran separate
     * findAll passes for Tj and then TJ, losing their relative ordering and
     * producing all Tj text before all TJ text on the same "line".
     */
    private fun extractTextOps(stream: String, out: StringBuilder) {
        // Each BT…ET block is one text object (may contain many visual lines)
        val btEt = Regex("""BT\b([\s\S]+?)\bET\b""")
        btEt.findAll(stream).forEach { block ->
            val content = block.groupValues[1]
            val line = StringBuilder()   // accumulates text for the current visual line

            // Combined regex — matches operators in document order
            val opRe = Regex(
                """\(([^)\\]*(?:\\.[^)\\]*)*)\)\s*Tj""" +   // alt1 (text)Tj  → group 1
                """|\[([^\]]*)\]\s*TJ""" +                    // alt2 [arr]TJ   → group 2
                """|(T\*|Td|TD)"""                            // alt3 line-move → group 3
            )
            opRe.findAll(content).forEach { m ->
                when {
                    m.groupValues[3].isNotEmpty() -> {
                        // Line-positioning operator → flush current line
                        val s = line.toString().trim()
                        if (s.isNotBlank()) out.append(s).append('\n')
                        line.clear()
                    }
                    m.groupValues[2].isNotEmpty() -> {
                        // [(array)]TJ — kerned text; extract each string piece
                        Regex("""\(([^)\\]*(?:\\.[^)\\]*)*)\)""")
                            .findAll(m.groupValues[2])
                            .forEach { sm -> line.append(unescapePdfString(sm.groupValues[1])) }
                    }
                    else -> {
                        // (string)Tj
                        val t = unescapePdfString(m.groupValues[1])
                        if (t.isNotBlank()) line.append(t).append(' ')
                    }
                }
            }
            // Flush any remaining text in the block
            val s = line.toString().trim()
            if (s.isNotBlank()) out.append(s).append('\n')
        }
    }

    /**
     * Convert PDF string escape sequences to real characters.
     *
     * Handles:
     *  - Standard PDF escape sequences (\n \r \t \( \) \\ \nnn octal)
     *  - UTF-16BE BOM (0xFE 0xFF) — the dominant encoding for Vietnamese,
     *    Chinese, Japanese and any PDF generated from Word / Google Docs.
     *    Without this, each Vietnamese character's high byte (often < 32)
     *    was silently discarded, leaving only ASCII chars (a "few lines").
     */
    private fun unescapePdfString(s: String): String {
        // Collect raw byte values (0–255) after resolving escape sequences
        val raw = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when {
                    s[i+1] == 'n'  -> { raw.add('\n'.code); i += 2 }
                    s[i+1] == 'r'  -> { raw.add('\r'.code); i += 2 }
                    s[i+1] == 't'  -> { raw.add('\t'.code); i += 2 }
                    s[i+1] == '('  -> { raw.add('('.code);  i += 2 }
                    s[i+1] == ')'  -> { raw.add(')'.code);  i += 2 }
                    s[i+1] == '\\' -> { raw.add('\\'.code); i += 2 }
                    i + 3 < s.length &&
                    s[i+1].isDigit() && s[i+2].isDigit() && s[i+3].isDigit() -> {
                        raw.add(s.substring(i+1, i+4).toIntOrNull(8) ?: 0)
                        i += 4
                    }
                    else -> { raw.add(s[i+1].code and 0xFF); i += 2 }
                }
            } else {
                raw.add(s[i].code and 0xFF)
                i++
            }
        }
        if (raw.isEmpty()) return ""

        // Detect UTF-16BE BOM: 0xFE 0xFF — used by Vietnamese / CJK PDFs
        if (raw.size >= 2 && raw[0] == 0xFE && raw[1] == 0xFF) {
            val bytes = ByteArray(raw.size - 2) { raw[it + 2].toByte() }
            return try { String(bytes, Charsets.UTF_16BE) } catch (_: Exception) { "" }
        }

        // Plain ISO-8859-1 / WinAnsi: keep printable characters
        return raw
            .filter { it >= 32 || it == 10 || it == 13 || it == 9 }
            .map { it.toChar() }
            .joinToString("")
    }

    // ─── DOCX (Office Open XML — ZIP + XML) ─────────────────────────────────

    private fun readDocx(context: Context, uri: Uri): Result<String> = try {
        val text = context.contentResolver.openInputStream(uri)?.use { parseDocxXml(it) } ?: ""
        Result.success(text)
    } catch (e: Exception) {
        Result.failure(Exception("Lỗi đọc DOCX: ${e.message}"))
    }

    private fun parseDocxXml(inputStream: InputStream): String {
        val sb = StringBuilder()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(zip, "UTF-8")
                    var inText = false
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "w:t", "t"  -> inText = true
                                "w:p", "p"  -> sb.append('\n')
                                "w:tab"     -> sb.append('\t')
                                "w:br"      -> sb.append('\n')
                            }
                            XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                            XmlPullParser.END_TAG -> if (parser.name == "w:t" || parser.name == "t") inText = false
                        }
                        event = parser.next()
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }
        return sb.toString().trim()
    }

    // ─── ODT (OpenDocument Text — ZIP + XML) ────────────────────────────────

    private fun readOdt(context: Context, uri: Uri): Result<String> = try {
        val text = context.contentResolver.openInputStream(uri)?.use { parseOdtXml(it) } ?: ""
        Result.success(text)
    } catch (e: Exception) {
        Result.failure(Exception("Lỗi đọc ODT: ${e.message}"))
    }

    private fun parseOdtXml(inputStream: InputStream): String {
        val sb = StringBuilder()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(zip, "UTF-8")
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "text:p", "text:h" -> sb.append('\n')
                                "text:tab"         -> sb.append('\t')
                                "text:line-break"  -> sb.append('\n')
                            }
                            XmlPullParser.TEXT -> sb.append(parser.text)
                        }
                        event = parser.next()
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }
        return sb.toString().trim()
    }

    // ─── RTF (basic strip) ──────────────────────────────────────────────────

    private fun readRtf(context: Context, uri: Uri): Result<String> = try {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.ISO_8859_1).readText()
        } ?: ""
        Result.success(stripRtf(raw))
    } catch (e: Exception) {
        Result.failure(Exception("Lỗi đọc RTF: ${e.message}"))
    }

    private fun stripRtf(rtf: String): String {
        val sb = StringBuilder()
        var i = 0
        var depth = 0
        while (i < rtf.length) {
            when {
                rtf[i] == '{' -> { depth++; i++ }
                rtf[i] == '}' -> { depth--; i++ }
                rtf[i] == '\\' -> {
                    i++
                    if (i < rtf.length) when {
                        rtf[i] == '\n' || rtf[i] == '\r' -> { sb.append('\n'); i++ }
                        rtf[i] == '\\' || rtf[i] == '{' || rtf[i] == '}' -> { sb.append(rtf[i]); i++ }
                        rtf[i] == '\'' && i + 2 < rtf.length -> {
                            val hex = rtf.substring(i + 1, i + 3)
                            val code = hex.toIntOrNull(16) ?: 0
                            if (code >= 32) sb.append(code.toChar())
                            i += 3
                        }
                        rtf[i].isLetter() -> {
                            while (i < rtf.length && (rtf[i].isLetterOrDigit() || rtf[i] == '-')) i++
                            if (i < rtf.length && rtf[i] == ' ') i++
                        }
                        else -> i++
                    }
                }
                depth == 0 -> { sb.append(rtf[i]); i++ }
                else -> i++
            }
        }
        return sb.toString()
            .replace(Regex("\r\n|\r"), "\n")
            .trim()
    }
}
