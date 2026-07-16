package com.texttospeech.app

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.texttospeech.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TtsManager
    private lateinit var bookmarks: BookmarkManager
    private lateinit var history: HistoryManager
    private lateinit var historyAdapter: HistoryAdapter

    private var currentFileName = "Văn bản thủ công"
    private var isDark = false

    // ─── File picker ────────────────────────────────────────────────────────

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadFile(it) } }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookmarks = BookmarkManager(this)
        history   = HistoryManager(this)

        setupTts()
        setupToolbar()
        setupLanguageSpinner()
        setupTextArea()
        setupFileControls()
        setupSpeedControls()
        setupPlaybackButtons()
        setupUtilButtons()
        updateSpeedDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.release()
    }

    // ─── TTS setup ──────────────────────────────────────────────────────────

    private fun setupTts() {
        tts = TtsManager(
            context       = this,
            onInitialized = {
                runOnUiThread {
                    binding.btnPlay.isEnabled = true
                    toast("Text-to-Speech sẵn sàng")
                }
            },
            onProgress    = { cur, total, pct ->
                runOnUiThread {
                    binding.progressBar.progress = pct
                    binding.tvProgress.text = "Đoạn $cur / $total"
                }
            },
            onChunkStart  = { _, _ -> /* could highlight text here */ },
            onFinished    = {
                runOnUiThread {
                    setPlaybackUi(playing = false, paused = false)
                    binding.tvProgress.text = "Đã đọc xong ✓"
                    binding.progressBar.progress = 100
                }
            },
            onError       = { msg -> runOnUiThread { toast(msg) } }
        )
        tts.init()
    }

    // ─── Toolbar ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.btnTheme.setOnClickListener {
            isDark = !isDark
            AppCompatDelegate.setDefaultNightMode(
                if (isDark) AppCompatDelegate.MODE_NIGHT_YES
                else        AppCompatDelegate.MODE_NIGHT_NO
            )
            binding.btnTheme.text = if (isDark) "☀️" else "🌙"
        }
    }

    // ─── Language ───────────────────────────────────────────────────────────

    private fun setupLanguageSpinner() {
        val langs = arrayOf("🇻🇳 Tiếng Việt", "🇺🇸 English", "🌐 Tự động nhận diện")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langs)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLang.adapter = adapter

        binding.spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val locale = when (pos) {
                    0    -> Locale("vi", "VN")
                    1    -> Locale.ENGLISH
                    else -> detectLocale()
                }
                tts.setLanguage(locale)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun detectLocale(): Locale {
        val text = binding.etContent.text?.toString() ?: return Locale("vi", "VN")
        val viChars = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ"
        return if (text.lowercase().any { it in viChars }) Locale("vi", "VN") else Locale.ENGLISH
    }

    // ─── Text area ──────────────────────────────────────────────────────────

    private fun setupTextArea() {
        // Prevent NestedScrollView from intercepting scroll inside EditText
        binding.etContent.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        binding.etContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                val tokens = len / 4
                binding.tvCharCount.text = "%,d ký tự  (~%,d tokens)".format(len, tokens)
                val color = when {
                    tokens > 900_000 -> getColor(R.color.warning_color)
                    else             -> getColor(R.color.text_secondary)
                }
                binding.tvCharCount.setTextColor(color)
            }
        })
    }

    // ─── File controls ──────────────────────────────────────────────────────

    private fun setupFileControls() {
        binding.btnOpenFile.setOnClickListener {
            filePicker.launch(arrayOf(
                "text/plain",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.oasis.opendocument.text",
                "application/rtf",
                "text/rtf",
                "text/markdown",
                "*/*"
            ))
        }

        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                binding.etContent.setText(text)
                toast("Đã dán ${"%,d".format(text.length)} ký tự")
            } else {
                toast("Clipboard trống")
            }
        }

        binding.btnClear.setOnClickListener {
            if (binding.etContent.text.isNullOrBlank()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Xóa nội dung")
                .setMessage("Xóa toàn bộ nội dung hiện tại?")
                .setPositiveButton("Xóa") { _, _ ->
                    binding.etContent.setText("")
                    currentFileName = "Văn bản thủ công"
                    tts.stop()
                    setPlaybackUi(playing = false, paused = false)
                    binding.progressBar.progress = 0
                    binding.tvProgress.text = "Sẵn sàng"
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    private fun loadFile(uri: Uri) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.btnOpenFile.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileReaderUtil.readFile(this@MainActivity, uri)
            }
            binding.progressLoading.visibility = View.GONE
            binding.btnOpenFile.isEnabled = true

            result
                .onSuccess { text ->
                    currentFileName = FileReaderUtil.getFileName(this@MainActivity, uri)
                    binding.etContent.setText(text)
                    toast("Đã tải: $currentFileName (${"%,d".format(text.length)} ký tự)")
                    history.add(currentFileName, text, uri.toString())
                }
                .onFailure { err ->
                    toast(err.message ?: "Lỗi không xác định")
                }
        }
    }

    // ─── Speed controls ─────────────────────────────────────────────────────

    private fun setupSpeedControls() {
        binding.btnSpeedDown.setOnClickListener {
            val newRate = (tts.getSpeechRate() - 0.1f).coerceAtLeast(TtsManager.MIN_SPEED)
            tts.setSpeed(newRate)
            updateSpeedDisplay()
        }
        binding.btnSpeedUp.setOnClickListener {
            val newRate = (tts.getSpeechRate() + 0.1f).coerceAtMost(TtsManager.MAX_SPEED)
            tts.setSpeed(newRate)
            updateSpeedDisplay()
        }
        binding.btnSpeedReset.setOnClickListener {
            tts.setSpeed(TtsManager.DEFAULT_SPEED)
            updateSpeedDisplay()
        }
    }

    private fun updateSpeedDisplay() {
        binding.tvSpeed.text = "%.1fx".format(tts.getSpeechRate())
    }

    // ─── Playback buttons ───────────────────────────────────────────────────

    private fun setupPlaybackButtons() {
        binding.btnPlay.setOnClickListener {
            val text = binding.etContent.text?.toString()?.trim() ?: ""
            if (text.isBlank()) { toast("Vui lòng nhập nội dung"); return@setOnClickListener }

            if (tts.isPaused) {
                tts.resume()
                setPlaybackUi(playing = true, paused = false)
            } else if (!tts.isPlaying) {
                currentFileName.let { history.add(it, text) }
                tts.setText(text)
                tts.play()
                setPlaybackUi(playing = true, paused = false)
            }
        }

        binding.btnPause.setOnClickListener {
            when {
                tts.isPlaying -> {
                    tts.pause()
                    setPlaybackUi(playing = false, paused = true)
                }
                tts.isPaused -> {
                    tts.resume()
                    setPlaybackUi(playing = true, paused = false)
                }
            }
        }

        binding.btnStop.setOnClickListener {
            tts.stop()
            setPlaybackUi(playing = false, paused = false)
            binding.progressBar.progress = 0
            binding.tvProgress.text = "Sẵn sàng"
        }
    }

    private fun setPlaybackUi(playing: Boolean, paused: Boolean) {
        binding.btnPlay.apply {
            isEnabled = !playing
            text = if (paused) "▶ Tiếp tục" else "▶ PHÁT"
        }
        binding.btnPause.apply {
            isEnabled = playing || paused
            text = if (playing) "⏸ Tạm dừng" else "▶ Tiếp tục"
        }
        binding.btnStop.isEnabled = playing || paused
    }

    // ─── Utility buttons (Bookmark / History) ───────────────────────────────

    private fun setupUtilButtons() {
        binding.btnBookmark.setOnClickListener { showBookmarkMenu() }
        binding.btnHistory.setOnClickListener  { showHistoryDialog() }
    }

    private fun showBookmarkMenu() {
        val text = binding.etContent.text?.toString() ?: ""
        if (text.isBlank()) { toast("Không có nội dung để bookmark"); return }

        MaterialAlertDialogBuilder(this)
            .setTitle("🔖 Bookmark")
            .setItems(arrayOf(
                "➕ Lưu vị trí hiện tại (đoạn ${tts.getCurrentChunkIndex() + 1})",
                "📋 Xem danh sách bookmark"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val idx = tts.getCurrentChunkIndex()
                        bookmarks.add(
                            label      = "Đoạn ${idx + 1} — $currentFileName",
                            chunkIndex = idx,
                            preview    = text.take(80)
                        )
                        toast("Đã lưu bookmark tại đoạn ${idx + 1}")
                    }
                    1 -> showBookmarkList()
                }
            }.show()
    }

    private fun showBookmarkList() {
        val list = bookmarks.getAll()
        if (list.isEmpty()) { toast("Chưa có bookmark nào"); return }

        val labels = list.map { "📌 ${it.label}\n${it.preview}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Danh sách Bookmark")
            .setItems(labels) { _, idx ->
                val bm = list[idx]
                tts.seekToChunk(bm.chunkIndex)
                toast("Đã chuyển đến: ${bm.label}")
            }
            .setNeutralButton("Xóa tất cả") { _, _ ->
                bookmarks.clear()
                toast("Đã xóa tất cả bookmark")
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showHistoryDialog() {
        val list = history.getAll()
        if (list.isEmpty()) { toast("Chưa có lịch sử"); return }

        // Build RecyclerView-based bottom sheet dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvHistory)
        historyAdapter = HistoryAdapter(
            onClick = { item ->
                // If has URI, reload the file; otherwise show info
                if (item.uriString.isNotBlank()) {
                    try {
                        loadFile(Uri.parse(item.uriString))
                    } catch (_: Exception) {
                        toast("Không thể mở lại file (có thể đã bị di chuyển)")
                    }
                } else {
                    toast("Không có đường dẫn file để mở lại")
                }
            },
            onDelete = { item ->
                history.remove(item.id)
                historyAdapter.submitList(history.getAll())
            }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = historyAdapter
        historyAdapter.submitList(list)

        MaterialAlertDialogBuilder(this)
            .setTitle("📋 Lịch sử đọc")
            .setView(dialogView)
            .setNeutralButton("Xóa tất cả") { _, _ ->
                history.clear()
                historyAdapter.submitList(emptyList())
                toast("Đã xóa lịch sử")
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
