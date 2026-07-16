package com.texttospeech.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

object FileReaderUtil {

    fun init(context: Context) {
        PDFBoxResourceLoader.init(context)
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    fun readFile(context: Context, uri: Uri): Result<String> {
        return try {
            val mime = context.contentResolver.getType(uri) ?: ""
            val name = getFileName(context, uri).lowercase()

            when {
                mime.contains("pdf") || name.endsWith(".pdf") ->
                    readPdf(context, uri)

                mime.contains("wordprocessingml") || name.endsWith(".docx") ->
                    readDocx(context, uri)

                name.endsWith(".odt") ->
                    readOdt(context, uri)

                name.endsWith(".rtf") ->
                    readRtf(context, uri)

                else ->
                    readPlainText(context, uri)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Không thể đọc file: ${e.message}"))
        }
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

    // ─── Plain text (.txt, .md, .csv, .log …) ───────────────────────────────

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

    // ─── PDF ────────────────────────────────────────────────────────────────

    private fun readPdf(context: Context, uri: Uri): Result<String> = try {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val doc = PDDocument.load(stream)
            val text = PDFTextStripper().getText(doc)
            doc.close()
            text
        } ?: ""
        Result.success(text)
    } catch (e: Exception) {
        Result.failure(Exception("Lỗi đọc PDF: ${e.message}"))
    }

    // ─── DOCX (Office Open XML) ──────────────────────────────────────────────

    private fun readDocx(context: Context, uri: Uri): Result<String> = try {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            parseDocxXml(stream)
        } ?: ""
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
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(zip, "UTF-8")

                    var inTextTag = false
                    var event = parser.eventType

                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                when (parser.name) {
                                    "w:t", "t" -> inTextTag = true
                                    "w:p", "p" -> sb.append('\n')      // New paragraph
                                    "w:tab"     -> sb.append('\t')      // Tab
                                    "w:br"      -> sb.append('\n')      // Line break
                                }
                            }
                            XmlPullParser.TEXT -> {
                                if (inTextTag) sb.append(parser.text)
                            }
                            XmlPullParser.END_TAG -> {
                                if (parser.name == "w:t" || parser.name == "t") inTextTag = false
                            }
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

    // ─── ODT (OpenDocument Text) ─────────────────────────────────────────────

    private fun readOdt(context: Context, uri: Uri): Result<String> = try {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            parseOdtXml(stream)
        } ?: ""
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
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(zip, "UTF-8")

                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                when (parser.name) {
                                    "text:p", "text:h"   -> sb.append('\n')
                                    "text:tab"           -> sb.append('\t')
                                    "text:line-break"    -> sb.append('\n')
                                }
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

    // ─── RTF (basic strip) ───────────────────────────────────────────────────

    private fun readRtf(context: Context, uri: Uri): Result<String> = try {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.ISO_8859_1).readText()
        } ?: ""
        Result.success(stripRtf(raw))
    } catch (e: Exception) {
        Result.failure(Exception("Lỗi đọc RTF: ${e.message}"))
    }

    /** Minimal RTF stripper — removes control words and braces. */
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
                    if (i < rtf.length) {
                        when {
                            rtf[i] == '\n' || rtf[i] == '\r' -> { sb.append('\n'); i++ }
                            rtf[i] == '\\' || rtf[i] == '{' || rtf[i] == '}' -> { sb.append(rtf[i]); i++ }
                            rtf[i] == '\'' -> {
                                // Hex escape: \'XX
                                if (i + 2 < rtf.length) {
                                    val hex = rtf.substring(i + 1, i + 3)
                                    val code = hex.toIntOrNull(16) ?: 0
                                    sb.append(code.toChar())
                                    i += 3
                                } else i++
                            }
                            rtf[i].isLetter() -> {
                                // Skip control word
                                while (i < rtf.length && (rtf[i].isLetterOrDigit() || rtf[i] == '-')) i++
                                if (i < rtf.length && rtf[i] == ' ') i++ // Optional delimiter
                            }
                            else -> i++
                        }
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
