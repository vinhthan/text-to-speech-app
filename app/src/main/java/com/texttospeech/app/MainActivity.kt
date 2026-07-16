package com.texttospeech.app

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
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
    private lateinit var bookmarks: BookmarkManager
    private lateinit var history: HistoryManager
    private lateinit var historyAdapter: HistoryAdapter

    private var currentFileName = "Văn bản thủ công"
    private var isDark = false
    /** True while the user is dragging the seek bar — suppresses progress updates. */
    private var isSeeking = false

    // ─── Service binding ─────────────────────────────────────────────────────

    private var ttsService: TtsService? = null
    private var bound = false

    /** Shortcut to the TtsManager owned by the service. */
    private val tts get() = ttsService?.ttsManager

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            ttsService = (binder as TtsService.TtsBinder).getService()
            bound = true
            ttsService?.setActivityListener(activityListener)
            // Sync UI with the current service / TTS state
            val mgr = tts ?: return
            if (mgr.isInitialized) binding.btnPlay.isEnabled = true
            setPlaybackUi(playing = mgr.isPlaying, paused = mgr.isPaused)
            updateSpeedDisplay()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            ttsService = null
        }
    }

    /** UI-update callbacks — TTS callbacks come from a background thread. */
    private val activityListener = object : TtsManager.Listener {
        override fun onInitialized() = runOnUiThread {
            binding.btnPlay.isEnabled = true
            updateVoiceLabel()
            toast("Text-to-Speech sẵn sàng")
        }
        override fun onProgress(current: Int, total: Int, percentage: Int) = runOnUiThread {
            binding.progressBar.progress = percentage
            binding.tvProgress.text = "Đoạn $current / $total"
            if (!isSeeking) {
                binding.seekBar.max      = (total - 1).coerceAtLeast(0)
                binding.seekBar.progress = current - 1
            }
        }
        override fun onChunkStart(text: String, index: Int) = Unit
        override fun onFinished() = runOnUiThread {
            setPlaybackUi(playing = false, paused = false)
            binding.tvProgress.text = "Đã đọc xong ✓"
            binding.progressBar.progress = 100
            binding.seekBar.progress = binding.seekBar.max
        }
        override fun onError(message: String) = runOnUiThread { toast(message) }
    }

    // ─── File picker ─────────────────────────────────────────────────────────

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadFile(it) } }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookmarks = BookmarkManager(this)
        history   = HistoryManager(this)

        setupToolbar()
        setupLanguageSpinner()
        setupTextArea()
        setupFileControls()
        setupSpeedControls()
        setupPlaybackButtons()
        setupSeekBar()
        setupUtilButtons()
        updateSpeedDisplay()
    }

    override fun onStart() {
        super.onStart()
        ensureServiceStarted()
        bindService(Intent(this, TtsService::class.java), serviceConn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            ttsService?.setActivityListener(null)
            unbindService(serviceConn)
            bound = false
            ttsService = null
        }
    }

    private fun ensureServiceStarted() {
        val intent = Intent(this, TtsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ─── Toolbar ─────────────────────────────────────────────────────────────

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

    // ─── Language + Voice ────────────────────────────────────────────────────

    private fun setupLanguageSpinner() {
        val langs = arrayOf("🇻🇳 Tiếng Việt", "🇺🇸 English", "🌐 Tự động nhận diện")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langs)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLang.adapter = adapter

        binding.spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val mgr = tts ?: return
                when (pos) {
                    0    -> { mgr.setAutoLanguage(false); mgr.setLanguage(Locale("vi", "VN")) }
                    1    -> { mgr.setAutoLanguage(false); mgr.setLanguage(Locale.ENGLISH) }
                    else -> { mgr.setAutoLanguage(true) }
                }
                updateVoiceLabel()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.btnVoicePick.setOnClickListener { showVoicePicker() }
    }

    private fun updateVoiceLabel() {
        val voice = tts?.getCurrentVoice()
        binding.tvVoiceLabel.text = if (voice != null) {
            "🎤 ${voiceDisplayName(voice)}"
        } else {
            "🎤 Giọng đọc: Mặc định"
        }
    }

    private fun showVoicePicker() {
        val mgr = tts ?: run { toast("TTS chưa sẵn sàng"); return }
        val locale = mgr.getCurrentLocale()
        val voices = mgr.getVoicesForLocale(locale)

        if (voices.isEmpty()) {
            toast("Không tìm thấy voice nào — hãy cài Google TTS")
            return
        }

        val current  = mgr.getCurrentVoice()
        val labels   = voices.map { voiceDisplayName(it) }.toTypedArray()
        val checkedIdx = voices.indexOfFirst { it.name == current?.name }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("🎤 Chọn giọng đọc")
            .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                mgr.setVoice(voices[which])
                updateVoiceLabel()
                dialog.dismiss()
                toast("Đã chọn: ${voiceDisplayName(voices[which])}")
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Formats a TTS Voice into a human-readable label. */
    private fun voiceDisplayName(voice: android.speech.tts.Voice): String {
        val name = voice.name
        // Google TTS naming: "vi-vn-x-vif-local" → "f" suffix = female
        val isFemale = name.matches(Regex(".*[a-z]f-(?:local|network)$")) ||
                       name.contains("female", ignoreCase = true)
        val gender  = if (isFemale) "♀" else "♂"
        val quality = when {
            voice.quality >= 400 -> "Rất cao"
            voice.quality >= 300 -> "Cao"
            voice.quality >= 200 -> "Thường"
            else                 -> "Thấp"
        }
        val shortName = name.removeSuffix("-local").removeSuffix("-network")
        return "$shortName  $gender  [$quality]"
    }

    // ─── Text area ───────────────────────────────────────────────────────────

    private fun setupTextArea() {
        // Prevent NestedScrollView from stealing scroll events inside the EditText
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

    // ─── File controls ───────────────────────────────────────────────────────

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
                    tts?.stop()
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
                .onFailure { err -> toast(err.message ?: "Lỗi không xác định") }
        }
    }

    // ─── Speed controls ──────────────────────────────────────────────────────

    private fun setupSpeedControls() {
        binding.btnSpeedDown.setOnClickListener {
            val mgr = tts ?: return@setOnClickListener
            mgr.setSpeed((mgr.getSpeechRate() - 0.1f).coerceAtLeast(TtsManager.MIN_SPEED))
            updateSpeedDisplay()
        }
        binding.btnSpeedUp.setOnClickListener {
            val mgr = tts ?: return@setOnClickListener
            mgr.setSpeed((mgr.getSpeechRate() + 0.1f).coerceAtMost(TtsManager.MAX_SPEED))
            updateSpeedDisplay()
        }
        binding.btnSpeedReset.setOnClickListener {
            tts?.setSpeed(TtsManager.DEFAULT_SPEED)
            updateSpeedDisplay()
        }
    }

    private fun updateSpeedDisplay() {
        binding.tvSpeed.text = "%.1fx".format(tts?.getSpeechRate() ?: TtsManager.DEFAULT_SPEED)
    }

    // ─── Playback buttons ────────────────────────────────────────────────────

    private fun setupPlaybackButtons() {
        binding.btnPlay.setOnClickListener {
            val raw = binding.etContent.text?.toString()?.trim() ?: ""
            if (raw.isBlank()) { toast("Vui lòng nhập nội dung"); return@setOnClickListener }
            val mgr = tts ?: return@setOnClickListener

            when {
                mgr.isPaused -> {
                    mgr.resume()
                    setPlaybackUi(playing = true, paused = false)
                }
                !mgr.isPlaying -> {
                    history.add(currentFileName, raw)
                    val normalized = TextNormalizer.normalize(raw, mgr.getCurrentLocale())
                    mgr.setText(normalized)
                    // Initialise seek bar range now that chunks are built
                    binding.seekBar.max      = (mgr.getTotalChunks() - 1).coerceAtLeast(0)
                    binding.seekBar.progress = 0
                    mgr.play()
                    setPlaybackUi(playing = true, paused = false)
                }
            }
        }

        binding.btnPause.setOnClickListener {
            val mgr = tts ?: return@setOnClickListener
            when {
                mgr.isPlaying -> { mgr.pause();  setPlaybackUi(playing = false, paused = true) }
                mgr.isPaused  -> { mgr.resume(); setPlaybackUi(playing = true,  paused = false) }
            }
        }

        binding.btnStop.setOnClickListener {
            tts?.stop()
            setPlaybackUi(playing = false, paused = false)
            binding.progressBar.progress = 0
            binding.seekBar.progress = 0
            binding.tvProgress.text = "Sẵn sàng"
        }
    }

    // ─── Seek bar ────────────────────────────────────────────────────────────

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val total = (seekBar.max + 1).coerceAtLeast(1)
                    binding.tvProgress.text = "Đoạn ${progress + 1} / $total"
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeeking = false
                val mgr = tts ?: return
                mgr.seekToChunk(seekBar.progress)
                if (!mgr.isPlaying && !mgr.isPaused) {
                    // If not currently playing, just update the UI position text
                    val total = (seekBar.max + 1).coerceAtLeast(1)
                    binding.tvProgress.text = "Đoạn ${seekBar.progress + 1} / $total"
                }
            }
        })
    }

    private fun setPlaybackUi(playing: Boolean, paused: Boolean) {
        binding.btnPlay.apply {
            isEnabled = !playing
            text = if (paused) "▶ Tiếp tục" else "▶  PHÁT"
        }
        binding.btnPause.apply {
            isEnabled = playing || paused
            text = if (playing) "⏸ Tạm dừng" else "▶ Tiếp tục"
        }
        binding.btnStop.isEnabled = playing || paused
    }

    // ─── Utility buttons (Bookmark / History) ────────────────────────────────

    private fun setupUtilButtons() {
        binding.btnBookmark.setOnClickListener { showBookmarkMenu() }
        binding.btnHistory.setOnClickListener  { showHistoryDialog() }
    }

    private fun showBookmarkMenu() {
        val text = binding.etContent.text?.toString() ?: ""
        if (text.isBlank()) { toast("Không có nội dung để bookmark"); return }
        val chunkIdx = tts?.getCurrentChunkIndex() ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle("🔖 Bookmark")
            .setItems(arrayOf(
                "➕ Lưu vị trí hiện tại (đoạn ${chunkIdx + 1})",
                "📋 Xem danh sách bookmark"
            )) { _, which ->
                when (which) {
                    0 -> {
                        bookmarks.add(
                            label      = "Đoạn ${chunkIdx + 1} — $currentFileName",
                            chunkIndex = chunkIdx,
                            preview    = text.take(80)
                        )
                        toast("Đã lưu bookmark tại đoạn ${chunkIdx + 1}")
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
                tts?.seekToChunk(bm.chunkIndex)
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

        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvHistory)
        historyAdapter = HistoryAdapter(
            onClick = { item ->
                if (item.uriString.isNotBlank()) {
                    try { loadFile(Uri.parse(item.uriString)) }
                    catch (_: Exception) { toast("Không thể mở lại file (có thể đã bị di chuyển)") }
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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
