package com.sphinx.voiceagent.voice

import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sphinx.voiceagent.data.model.agent.response.CollectionResponse
import com.sphinx.voiceagent.data.repository.AgentRepository
import kotlinx.coroutines.launch
import okhttp3.internal.wait

class VoiceSettingsActivity : AppCompatActivity() {

    private val repo = AgentRepository()

    private lateinit var tvCurrentVoice: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var layoutVoiceList: LinearLayout

    private var collections: List<CollectionResponse> = emptyList()
    private var currentCollectionId: String? = null
    private var selectedCollectionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        supportActionBar?.apply {
            title = "Cài đặt giọng nói"
            setDisplayHomeAsUpEnabled(true)
        }
        loadData()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ─────────────────────────────────────────────────────────────
    // Load song song: agent detail + collections
    // lifecycleScope tự cancel khi Activity destroy — không leak
    // ─────────────────────────────────────────────────────────────
    private fun loadData() {
        setLoading(true)

        lifecycleScope.launch {
            try {
                // Gọi song song
                val agentDeferred =  repo.getAgent()
                val collsDeferred = repo.getCollections()

                val agent  = agentDeferred
                val colls  = collsDeferred

                currentCollectionId  = agent.tts_collection_id
                selectedCollectionId = currentCollectionId
                collections          = colls

                setLoading(false)
                renderList()

            } catch (e: Exception) {
                setLoading(false)
                showStatus("❌ ${e.message}", error = true)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Render danh sách radio-list
    // ─────────────────────────────────────────────────────────────
    private fun renderList() {
        layoutVoiceList.removeAllViews()
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val currentName = collections.find { it.id == currentCollectionId }?.name
        tvCurrentVoice.text = "Đang dùng: ${currentName ?: "Chưa cài đặt"}"
        tvStatus.isVisible  = false

        if (collections.isEmpty()) {
            showStatus("Không có giọng nào", error = false); return
        }

        collections.forEach { col ->
            val isSelected = col.id == (selectedCollectionId ?: currentCollectionId)
            val isCurrent  = col.id == currentCollectionId

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
                setBackgroundColor(if (isSelected) 0xFFE3F2FD.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 1.dp() }
                setOnClickListener { selectedCollectionId = col.id; renderList() }
            }

            // Radio dot
            row.addView(TextView(this).apply {
                text = if (isSelected) "●" else "○"
                textSize = 18f
                setTextColor(if (isSelected) 0xFF1565C0.toInt() else 0xFFBDBDBD.toInt())
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 14.dp() }
            })

            // Name + badge
            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            nameCol.addView(TextView(this).apply {
                text = col.name; textSize = 14f
                setTextColor(0xFF212121.toInt())
                if (isSelected) setTypeface(null, Typeface.BOLD)
            })
            if (isCurrent) nameCol.addView(TextView(this).apply {
                text = "Đang dùng"; textSize = 10f
                setTextColor(0xFF1565C0.toInt())
            })
            row.addView(nameCol)
            layoutVoiceList.addView(row)

            // Divider
            layoutVoiceList.addView(View(this).apply {
                setBackgroundColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, 1)
            })
        }

        // Nút Lưu
        val hasChange = selectedCollectionId != null && selectedCollectionId != currentCollectionId
        layoutVoiceList.addView(Button(this).apply {
            text = if (hasChange) "💾  Lưu thay đổi" else "Đây là giọng đang dùng"
            isEnabled = hasChange
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (hasChange) 0xFF1565C0.toInt() else 0xFFBDBDBD.toInt()
            )
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.dp() }
            setOnClickListener { saveVoice() }
        })
    }

    // ─────────────────────────────────────────────────────────────
    // Lưu giọng đã chọn
    // ─────────────────────────────────────────────────────────────
    private fun saveVoice() {
        val colId = selectedCollectionId ?: return
        setLoading(true)

        lifecycleScope.launch {
            try {
                repo.updateVoice(colId)
                currentCollectionId = colId
                val name = collections.find { it.id == colId }?.name ?: colId
                setLoading(false)
                showStatus("✅ Đã đổi sang: $name", error = false)
                renderList()
            } catch (e: Exception) {
                setLoading(false)
                showStatus("❌ ${e.message}", error = true)
            }
        }
    }

    private fun setLoading(on: Boolean) {
        progressBar.isVisible     = on
        layoutVoiceList.isVisible = !on
    }

    private fun showStatus(msg: String, error: Boolean) {
        tvStatus.text = msg
        tvStatus.setTextColor(if (error) 0xFFD32F2F.toInt() else 0xFF388E3C.toInt())
        tvStatus.isVisible = true
    }

    // ─────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────
    private fun buildLayout(): View {
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val scroll = ScrollView(this).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 24.dp())
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "🎙  CHỌN GIỌNG NÓI"
            textSize = 11f; letterSpacing = 0.1f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF7090B0.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.dp() }
        })

        tvCurrentVoice = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF424242.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp() }
        }.also { root.addView(it) }

        progressBar = ProgressBar(this).apply {
            isVisible = true
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 24.dp(); bottomMargin = 24.dp()
            }
        }.also { root.addView(it) }

        tvStatus = TextView(this).apply {
            textSize = 13f; isVisible = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 6.dp(); bottomMargin = 6.dp()
            }
        }.also { root.addView(it) }

        layoutVoiceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isVisible = false
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }.also { root.addView(it) }

        return scroll
    }
}
