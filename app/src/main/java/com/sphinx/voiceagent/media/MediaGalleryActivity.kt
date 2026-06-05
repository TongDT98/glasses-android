package com.sphinx.voiceagent.media

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sphinx.voiceagent.R
import com.sphinx.voiceagent.ui.MyApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────
// MediaType — khớp đúng với identifyFileType() trong SDK:
//   jpg / jpeg → IMAGE
//   mp4 / avi  → VIDEO   (SDK dùng identifyFileType với 2 ext này)
//   opus       → AUDIO (raw) → SDK convert → .pcm
//
// KHÔNG dùng .ts .mov .mkv vì SDK không nhận dạng chúng.
// ─────────────────────────────────────────────────────────────────
enum class MediaType { IMAGE, VIDEO, AUDIO }

data class MediaFile(
    val file: File,
    val type: MediaType,
    val sizeBytes: Long,
    val dateMs: Long,
    val thumbnailPath: String? = null,
) {
    val name: String get() = file.name
    val ext: String  get() = file.extension.lowercase()
    val sizeLabel: String get() = when {
        sizeBytes < 1024        -> "${sizeBytes}B"
        sizeBytes < 1_048_576   -> "${sizeBytes / 1024}KB"
        else -> String.format("%.1fMB", sizeBytes / 1_048_576.0)
    }
    val dateLabel: String get() =
        SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(dateMs))
}

class MediaGalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MediaGallery"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStoragePath: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnDeleteAll: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnCancelSelection: Button
    private lateinit var spinnerFilter: Spinner

    private val allFiles     = mutableListOf<MediaFile>()
    private val displayFiles = mutableListOf<MediaFile>()
    private val selectedFiles = linkedSetOf<MediaFile>()
    private lateinit var adapter: MediaAdapter
    private var mediaPlayer: MediaPlayer? = null

    private val albumDir: File by lazy { MyApplication.getInstance().getAlbumDirFile() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        supportActionBar?.apply {
            title = "Media từ kính"
            setDisplayHomeAsUpEnabled(true)
        }
        adapter = MediaAdapter(
            displayFiles,
            ::onItemClick,
            ::onItemLongClick,
            isSelectionMode = { selectedFiles.isNotEmpty() },
            isSelected = { selectedFiles.contains(it) }
        )
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = adapter
        setupFilter()
        loadFiles()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); loadFiles() }
    override fun onDestroy() { mediaPlayer?.release(); super.onDestroy() }

    // ─────────────────────────────────────────────────────────────
    // Load files
    // ─────────────────────────────────────────────────────────────
    private fun loadFiles() {
        allFiles.clear()
        if (!albumDir.exists()) { updateUI(); return }

        // Log toàn bộ file trong thư mục để debug
        Log.d(TAG, "=== Scanning albumDir: ${albumDir.absolutePath} ===")
        albumDir.walkTopDown().filter { it.isFile }.forEach { f ->
            Log.d(TAG, "  FILE: ${f.name}  size=${f.length()}  ext=${f.extension}")
        }

        // Thu thập thumbnail SDK đã gen sẵn: first_frame_<basename>.png
        val thumbnailMap = mutableMapOf<String, String>()
        albumDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("first_frame_") && it.name.endsWith(".png") }
            .forEach { thumb ->
                val base = thumb.nameWithoutExtension.removePrefix("first_frame_")
                thumbnailMap[base] = thumb.absolutePath
                Log.d(TAG, "  THUMBNAIL: ${thumb.name} → key=$base")
            }

        albumDir.walkTopDown().filter { it.isFile && it.length() > 0 }.forEach { f ->
            val ext = f.extension.lowercase()

            // Bỏ qua file hệ thống
            if (f.name.startsWith("first_frame_")) return@forEach
            if (ext in listOf("txt", "log", "config", "json")) return@forEach

            // Phân loại ĐÚNG theo SDK identifyFileType()
            val type: MediaType = when (ext) {
                "jpg", "jpeg"        -> MediaType.IMAGE
                // SDK nhận dạng: mp4, avi
                // Thực tế kính có thể lưu thêm .ts (MPEG transport stream)
                // hoặc không có extension rõ ràng → dùng magic bytes check
                "mp4", "avi"         -> MediaType.VIDEO
                // .ts có thể là video nếu đến từ kính (EIS processing)
                "ts"                 -> if (looksLikeVideo(f)) MediaType.VIDEO else return@forEach
                // Ghi âm: .opus raw từ kính, .pcm sau khi SDK convert
                "opus"               -> {
                    // Nếu đã có .pcm tương ứng → bỏ qua .opus gốc
                    val pcm = File(f.parent, "${f.nameWithoutExtension}.pcm")
                    if (pcm.exists() && pcm.length() > 0) return@forEach
                    MediaType.AUDIO
                }
                "pcm", "wav"         -> MediaType.AUDIO
                else                 -> {
                    // File không rõ extension → thử nhận dạng bằng magic bytes
                    val magic = readMagic(f)
                    when {
                        magic.startsWith("ffd8ff")         -> MediaType.IMAGE  // JPEG
                        magic.startsWith("89504e47")       -> MediaType.IMAGE  // PNG
                        magic.contains("66747970")         -> MediaType.VIDEO  // mp4/ftyp
                        magic.startsWith("4f676753")       -> MediaType.AUDIO  // OGG/Opus
                        else -> {
                            Log.w(TAG, "  UNKNOWN ext=$ext magic=$magic file=${f.name}")
                            return@forEach
                        }
                    }
                }
            }

            val thumb = if (type == MediaType.VIDEO) thumbnailMap[f.nameWithoutExtension] else null
            Log.d(TAG, "  ADDED: ${f.name} → $type  thumb=$thumb")
            allFiles.add(MediaFile(f, type, f.length(), f.lastModified(), thumb))
        }

        allFiles.sortByDescending { it.dateMs }
        Log.d(TAG, "Total loaded: ${allFiles.size} (img=${allFiles.count{it.type==MediaType.IMAGE}} vid=${allFiles.count{it.type==MediaType.VIDEO}} aud=${allFiles.count{it.type==MediaType.AUDIO}})")
        applyFilter(spinnerFilter.selectedItemPosition)
    }

    /** Đọc 8 byte đầu file dạng hex để nhận dạng magic bytes */
    private fun readMagic(f: File): String = try {
        f.inputStream().use { stream ->
            val buf = ByteArray(8)
            val n = stream.read(buf)
            buf.take(n).joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) { "" }

    /** Kiểm tra .ts file có phải video không (MPEG-TS magic = 0x47) */
    private fun looksLikeVideo(f: File): Boolean = try {
        f.inputStream().use { it.read() == 0x47 }
    } catch (_: Exception) { false }

    private fun applyFilter(idx: Int) {
        selectedFiles.removeAll { selected -> allFiles.none { it.file == selected.file } }
        displayFiles.clear()
        displayFiles.addAll(when (idx) {
            1    -> allFiles.filter { it.type == MediaType.IMAGE }
            2    -> allFiles.filter { it.type == MediaType.VIDEO }
            3    -> allFiles.filter { it.type == MediaType.AUDIO }
            else -> allFiles
        })
        updateUI()
    }

    private fun updateUI() {
        adapter.notifyDataSetChanged()
        val empty = displayFiles.isEmpty()
        tvEmpty.visibility     = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE   else View.VISIBLE

        val totalSize = allFiles.sumOf { it.sizeBytes }
        tvStats.text = buildString {
            append("📸 ${allFiles.count{it.type==MediaType.IMAGE}}  ")
            append("🎥 ${allFiles.count{it.type==MediaType.VIDEO}}  ")
            append("🎙 ${allFiles.count{it.type==MediaType.AUDIO}}")
            val mb = totalSize / 1_048_576.0
            append("  ·  ${if (mb < 1) "${totalSize/1024}KB" else String.format("%.1fMB", mb)}")
        }
        tvStoragePath.text = "📁 ${albumDir.absolutePath}"
        btnDeleteAll.isEnabled = allFiles.isNotEmpty()
        btnDeleteAll.visibility = if (selectedFiles.isEmpty()) View.VISIBLE else View.GONE
        btnDeleteSelected.visibility = if (selectedFiles.isEmpty()) View.GONE else View.VISIBLE
        btnCancelSelection.visibility = if (selectedFiles.isEmpty()) View.GONE else View.VISIBLE
        btnDeleteSelected.text = "Đã chọn (${selectedFiles.size})"
    }

    // ─────────────────────────────────────────────────────────────
    // Item interactions
    // ─────────────────────────────────────────────────────────────
//    private fun onItemClick(media: MediaFile) {
//        if (selectedFiles.isNotEmpty()) {
//            toggleSelection(media)
//            return
//        }
//        when (media.type) {
//            MediaType.AUDIO -> showAudioPlayer(media)
//            else            -> openWithSystem(media)
//        }
//    }
    private fun onItemClick(media: MediaFile) {
        // Nếu ĐANG trong chế độ chọn nhiều file -> Click vào item sẽ TỰ ĐỘNG check/uncheck
        if (selectedFiles.isNotEmpty()) {
            toggleSelection(media)
            return
        }

        // Nếu KHÔNG trong chế độ chọn -> Click để xem/nghe bình thường
        when (media.type) {
            MediaType.AUDIO -> showAudioPlayer(media)
            else            -> openWithSystem(media)
        }
    }

    private fun openWithSystem(media: MediaFile) {
        try {
            val uri  = FileProvider.getUriForFile(this, "${packageName}.provider", media.file)
            val mime = when (media.type) {
                MediaType.IMAGE -> "image/*"
                MediaType.VIDEO -> "video/*"
                MediaType.AUDIO -> "audio/*"
            }
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Không có app để mở file này", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Audio player tích hợp
    // .pcm (raw 16kHz mono 16-bit) → convert sang .wav rồi play
    // .opus → MediaPlayer trực tiếp (Android 5+)
    // ─────────────────────────────────────────────────────────────
    private fun showAudioPlayer(media: MediaFile) {
        val playFile: File = when (media.ext) {
            "pcm" -> {
                val wav = File(media.file.parent, "${media.file.nameWithoutExtension}.wav")
                if (!wav.exists()) {
                    try { convertPcmToWav(media.file, wav) }
                    catch (e: Exception) {
                        Toast.makeText(this, "Lỗi đọc PCM: ${e.message}", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                wav
            }
            else -> media.file
        }

        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        val tvStatus = TextView(ctx).apply { text = "Nhấn ▶ để phát"; textSize = 13f }
        val btnPlay  = Button(ctx).apply {
            text = "▶ Phát"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }
        layout.addView(tvStatus)
        layout.addView(btnPlay)

        val dlg = AlertDialog.Builder(ctx)
            .setTitle("🎙 ${media.name}")
            .setView(layout)
            .setNeutralButton("↗ Chia sẻ") { _, _ -> shareFile(media) }
            .setNegativeButton("Đóng") { _, _ -> stopPlayer() }
            .create()

        btnPlay.setOnClickListener {
            stopPlayer()
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(playFile.absolutePath)
                    prepare()
                    start()
                    tvStatus.text = "▶ Đang phát  (${formatMs(duration)})"
                    btnPlay.text  = "⏹ Dừng"
                    setOnCompletionListener {
                        tvStatus.text = "✅ Phát xong"
                        btnPlay.text  = "▶ Phát lại"
                    }
                }
            } catch (e: Exception) {
                tvStatus.text = "❌ ${e.message}"
            }
        }
        dlg.show()
    }

    private fun stopPlayer() { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null }

    private fun convertPcmToWav(pcm: File, wav: File, sr: Int = 16000, ch: Int = 1, bits: Int = 16) {
        val data     = pcm.readBytes()
        val byteRate = sr * ch * bits / 8
        wav.outputStream().use { out ->
            fun Int.le4() = byteArrayOf(and(0xFF).toByte(), shr(8).and(0xFF).toByte(),
                shr(16).and(0xFF).toByte(), shr(24).and(0xFF).toByte())
            fun Short.le2() = byteArrayOf(toInt().and(0xFF).toByte(), toInt().shr(8).and(0xFF).toByte())
            out.write("RIFF".toByteArray()); out.write((data.size + 36).le4())
            out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
            out.write(16.le4()); out.write(1.toShort().le2()); out.write(ch.toShort().le2())
            out.write(sr.le4()); out.write(byteRate.le4())
            out.write((ch * bits / 8).toShort().le2()); out.write(bits.toShort().le2())
            out.write("data".toByteArray()); out.write(data.size.le4()); out.write(data)
        }
    }

    private fun formatMs(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private fun onItemLongClick(media: MediaFile) {
        toggleSelection(media)
        Toast.makeText(this, "Da chon ${media.name}", Toast.LENGTH_SHORT).show()
        return
        AlertDialog.Builder(this)
            .setTitle(media.name)
            .setMessage("${media.sizeLabel}  ·  ${media.dateLabel}")
            .setPositiveButton("🗑 Xoá")    { _, _ -> deleteSingle(media) }
            .setNeutralButton("↗ Chia sẻ") { _, _ -> shareFile(media) }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun deleteSingle(media: MediaFile) {
        media.thumbnailPath?.let { File(it).delete() }
        if (media.ext == "pcm") File(media.file.parent, "${media.file.nameWithoutExtension}.wav").delete()
        if (media.file.delete()) {
            allFiles.remove(media); displayFiles.remove(media)
            adapter.notifyDataSetChanged(); updateUI()
            Toast.makeText(this, "Đã xoá ${media.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Xoá tất cả?")
            .setMessage("Xoá ${displayFiles.size} file. Không thể khôi phục.")
            .setPositiveButton("Xoá") { _, _ ->
                var n = 0
                displayFiles.toList().forEach { m ->
                    m.thumbnailPath?.let { File(it).delete() }
                    if (m.file.delete()) { allFiles.remove(m); n++ }
                }
                displayFiles.clear(); adapter.notifyDataSetChanged(); updateUI()
                Toast.makeText(this, "Đã xoá $n file", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun deleteSelected() {
        val files = selectedFiles.toList()
        if (files.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Xóa mục đã chọn ?")
            .setMessage("Xoa ${files.size} file. không thể khôi phục.")
            .setPositiveButton("Xóa") { _, _ ->
                var n = 0
                files.forEach { media ->
                    media.thumbnailPath?.let { File(it).delete() }
                    if (media.ext == "pcm") File(media.file.parent, "${media.file.nameWithoutExtension}.wav").delete()
                    if (media.file.delete()) {
                        allFiles.remove(media)
                        displayFiles.remove(media)
                        n++
                    }
                }
                selectedFiles.clear()
                adapter.notifyDataSetChanged()
                updateUI()
                Toast.makeText(this, "Đã xóa $n file", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Huy", null)
            .show()
    }

    private fun toggleSelection(media: MediaFile) {
        if (!selectedFiles.add(media)) selectedFiles.remove(media)
        adapter.notifyDataSetChanged()
        updateUI()
    }

    private fun clearSelection() {
        selectedFiles.clear()
        adapter.notifyDataSetChanged()
        updateUI()
    }

    private fun shareFile(media: MediaFile) {
        val uri  = FileProvider.getUriForFile(this, "${packageName}.provider", media.file)
        val mime = when (media.type) { MediaType.IMAGE -> "image/*"; MediaType.VIDEO -> "video/*"; else -> "audio/*" }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Chia sẻ qua…"))
    }

    private fun setupFilter() {
        spinnerFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Tất cả", "Ảnh", "Video", "Ghi âm"))
        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyFilter(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun buildLayout(): View {
        val d   = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        return LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)

            tvStoragePath = TextView(context).apply {
                textSize = 10f; setTextColor(0xFF888888.toInt())
                setPadding(16.dp(), 8.dp(), 16.dp(), 0); setTextIsSelectable(true)
            }.also { addView(it) }

            tvStats = TextView(context).apply {
                textSize = 13f; setTextColor(0xFF333333.toInt())
                setPadding(16.dp(), 4.dp(), 16.dp(), 8.dp())
            }.also { addView(it) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12.dp(), 4.dp(), 12.dp(), 8.dp()); gravity = Gravity.CENTER_VERTICAL

                spinnerFilter = Spinner(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }.also { addView(it) }

                btnDeleteAll = Button(context).apply {
                    text = "🗑 Xoá tất cả"; textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8.dp() }
                    setOnClickListener { deleteAll() }
                }.also { addView(it) }

                btnDeleteSelected = Button(context).apply {
                    text = "Xoa da chon"; textSize = 12f
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8.dp() }
                    setOnClickListener { deleteSelected() }
                }.also { addView(it) }

                btnCancelSelection = Button(context).apply {
                    text = "Huy"; textSize = 12f
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8.dp() }
                    setOnClickListener { clearSelection() }
                }.also { addView(it) }
            })

            addView(View(context).apply {
                setBackgroundColor(0xFFE0E0E0.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, 1)
            })

            tvEmpty = TextView(context).apply {
                text = "Chưa có file nào.\nHãy chụp ảnh / quay video / ghi âm\nrồi nhấn Đồng bộ."
                textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF888888.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -1)
                visibility   = View.GONE
            }.also { addView(it) }

            recyclerView = RecyclerView(context).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -1)
                setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp()); clipToPadding = false
            }.also { addView(it) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────
class MediaAdapter(
    private val items: List<MediaFile>,
    private val onClick: (MediaFile) -> Unit,
    private val onLongClick: (MediaFile) -> Unit,
    private val isSelectionMode: () -> Boolean,
    private val isSelected: (MediaFile) -> Boolean,
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val iv    = root.getChildAt(0) as ImageView
        val icon  = root.getChildAt(1) as TextView
        val badge = root.getChildAt(2) as TextView
        val check = root.getChildAt(3) as TextView
        val name  = root.getChildAt(4) as TextView
        val meta  = root.getChildAt(5) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH {
        val d = parent.context.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()
        return VH(LinearLayout(parent.context).apply {
            orientation  = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xFFF5F5F5.toInt())
            layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply { setMargins(4.dp(),4.dp(),4.dp(),4.dp()) }
            setPadding(8.dp(), 12.dp(), 8.dp(), 10.dp())
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 110.dp())
                scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE })
            addView(TextView(context).apply {
                textSize = 38f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2) })
            addView(TextView(context).apply {
                textSize = 9f; gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x99000000.toInt())
                setPadding(4.dp(),2.dp(),4.dp(),2.dp())
                layoutParams = LinearLayout.LayoutParams(-2,-2)
                visibility = View.GONE })
            addView(TextView(context).apply {
                textSize = 18f; gravity = Gravity.CENTER
                setTextColor(0xFF1565C0.toInt())
                layoutParams = LinearLayout.LayoutParams(-1,-2)
                visibility = View.GONE })
            addView(TextView(context).apply {
                textSize = 11f; setTextColor(0xFF222222.toInt()); gravity = Gravity.CENTER; maxLines = 2
                layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = 6.dp() } })
            addView(TextView(context).apply {
                textSize = 10f; setTextColor(0xFF888888.toInt()); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = 2.dp() } })
        })
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        h.name.text  = m.name
        h.meta.text  = "${m.sizeLabel}  ·  ${m.dateLabel}"
        h.badge.visibility = View.GONE
        val selected = isSelected(m)
        h.root.setBackgroundColor(if (selected) 0xFFE3F2FD.toInt() else 0xFFF5F5F5.toInt())
        h.check.visibility = if (isSelectionMode()) View.VISIBLE else View.GONE
        h.check.text = if (selected) "☑" else "☐"

        when (m.type) {
            MediaType.IMAGE -> {
                h.iv.visibility = View.VISIBLE; h.icon.visibility = View.GONE
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    h.iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(m.file.absolutePath, opts))
                } catch (_: Exception) { h.iv.visibility = View.GONE; h.icon.visibility = View.VISIBLE; h.icon.text = "🖼" }
            }
            MediaType.VIDEO -> {
                h.iv.visibility = View.VISIBLE; h.icon.visibility = View.GONE
                // Ưu tiên thumbnail SDK gen sẵn, fallback MediaMetadataRetriever
                var loaded = false
                m.thumbnailPath?.let { tp ->
                    try {
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                        android.graphics.BitmapFactory.decodeFile(tp, opts)?.let { h.iv.setImageBitmap(it); loaded = true }
                    } catch (_: Exception) {}
                }
                if (!loaded) {
                    try {
                        val r = android.media.MediaMetadataRetriever()
                        r.setDataSource(m.file.absolutePath)
                        r.getFrameAtTime(0)?.let { h.iv.setImageBitmap(it); loaded = true }
                        r.release()
                    } catch (_: Exception) {}
                }
                if (!loaded) { h.iv.visibility = View.GONE; h.icon.visibility = View.VISIBLE; h.icon.text = "🎥" }
            }
            MediaType.AUDIO -> {
                h.iv.visibility = View.GONE; h.icon.visibility = View.VISIBLE
                h.icon.text = if (m.ext == "pcm") "📊" else "🎙"
                h.badge.visibility = View.VISIBLE; h.badge.text = m.ext.uppercase()
            }
        }
        h.root.setOnClickListener     { onClick(m) }
        h.root.setOnLongClickListener { onLongClick(m); true }
    }

    override fun getItemCount() = items.size
}
