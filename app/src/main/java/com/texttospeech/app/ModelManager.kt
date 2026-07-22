package com.texttospeech.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages the Piper TTS model files from HuggingFace.
 *
 * Model: csukuangfj/vits-piper-vi_VN-vais1000-medium
 * Structure after download:
 *   filesDir/piper_vi_medium/
 *     ├── vi_VN-vais1000-medium.onnx   (~60MB, main model)
 *     ├── tokens.txt                   (~3KB)
 *     └── espeak-ng-data/              (~3MB, 200+ small files)
 */
class ModelManager(private val context: Context) {

    companion object {
        const val HF_REPO      = "csukuangfj/vits-piper-vi_VN-vais1000-medium"
        const val MODEL_NAME   = "vi_VN-vais1000-medium.onnx"
        private const val HF_API_URL = "https://huggingface.co/api/models/$HF_REPO"
        private const val HF_RAW_URL = "https://huggingface.co/$HF_REPO/resolve/main"

        // Files to skip when downloading
        private val SKIP_FILES = setOf(
            ".gitattributes", "MODEL_CARD", "vits-piper.py", "vits-piper.sh",
            "vi_VN-vais1000-medium.onnx.json"   // sherpa-onnx doesn't need this
        )
    }

    val modelDir   = File(context.filesDir, "piper_vi_medium")
    val espeakDir  = File(modelDir, "espeak-ng-data")

    // ─── State ───────────────────────────────────────────────────────────────

    fun isModelReady(): Boolean =
        File(modelDir, MODEL_NAME).exists() &&
        File(modelDir, "tokens.txt").exists() &&
        espeakDir.isDirectory && (espeakDir.list()?.isNotEmpty() == true)

    fun modelSizeBytes(): Long = File(modelDir, MODEL_NAME).length()

    fun deleteModel() {
        modelDir.deleteRecursively()
    }

    // ─── Download ────────────────────────────────────────────────────────────

    /**
     * Downloads the complete model from HuggingFace.
     * @param onProgress callback(bytesDownloaded, totalEstimatedBytes)
     * @throws Exception if download fails — caller must handle and clean up
     */
    suspend fun downloadModel(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()

        // 1. Get file list from HuggingFace model API
        val fileList = fetchFileList()

        // 2. Partition: large ONNX first, then everything else
        val onnxFile   = fileList.firstOrNull { it.endsWith(MODEL_NAME) }
            ?: throw Exception("Không tìm thấy file model $MODEL_NAME trên HuggingFace")
        val otherFiles = fileList.filter { it != onnxFile && it !in SKIP_FILES }

        // Estimated total: ONNX ~62MB + rest ~3MB
        val estimatedTotal = 65_000_000L
        var downloaded = 0L

        // 3. Download the large ONNX model first
        val onnxDest = File(modelDir, MODEL_NAME)
        downloadFile(
            urlStr   = "$HF_RAW_URL/$onnxFile",
            dest     = onnxDest,
            onBytes  = { bytes ->
                downloaded += bytes
                onProgress(downloaded, estimatedTotal)
            }
        )

        // 4. Download the rest (tokens.txt + espeak-ng-data/*)
        for (filePath in otherFiles) {
            val dest = File(modelDir, filePath)
            dest.parentFile?.mkdirs()
            downloadFile(
                urlStr  = "$HF_RAW_URL/$filePath",
                dest    = dest,
                onBytes = { bytes ->
                    downloaded += bytes
                    onProgress(downloaded, estimatedTotal)
                }
            )
        }

        // Clamp to 100%
        onProgress(estimatedTotal, estimatedTotal)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Fetches the model file list from the HuggingFace API.
     * Returns paths relative to the repo root.
     */
    private fun fetchFileList(): List<String> {
        val conn = openUrl(HF_API_URL)
        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val obj = JSONObject(json)
        val siblings = obj.getJSONArray("siblings")
        val result = mutableListOf<String>()
        for (i in 0 until siblings.length()) {
            val name = siblings.getJSONObject(i).getString("rfilename")
            result += name
        }
        return result
    }

    /**
     * Downloads [urlStr] to [dest], following redirects (required for GitHub/HuggingFace CDN).
     * Calls [onBytes] after each buffer write with the number of bytes written.
     */
    private fun downloadFile(urlStr: String, dest: File, onBytes: (Long) -> Unit) {
        val conn = openUrl(urlStr)
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Opens a URL following HTTP redirects across different hosts
     * (GitHub → Objects CDN, HuggingFace → AWS CDN).
     */
    private fun openUrl(urlStr: String): HttpURLConnection {
        var current = urlStr
        repeat(10) {   // max 10 redirects
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Android")
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000
            conn.connect()
            return when (conn.responseCode) {
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_PARTIAL -> conn
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                307, 308 -> {
                    val loc = conn.getHeaderField("Location")
                        ?: throw Exception("Redirect without Location header")
                    conn.disconnect()
                    // Resolve relative redirects (e.g. HuggingFace returns "/api/resolve-cache/...")
                    current = if (loc.startsWith("http://") || loc.startsWith("https://")) {
                        loc
                    } else {
                        URL(URL(current), loc).toString()
                    }
                    return@repeat   // continue loop
                }
                else -> throw Exception("HTTP ${conn.responseCode} for $current")
            }
        }
        throw Exception("Too many redirects for $urlStr")
    }
}
