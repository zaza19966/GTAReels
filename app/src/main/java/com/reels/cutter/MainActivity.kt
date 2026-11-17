package com.reels.cutter

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var videoUri: Uri? = null
    private var durationMs = 0L
    private var vw = 1920
    private var vh = 1080
    private var audio: FloatArray? = null
    private var motion: FloatArray? = null
    private var cachedUri: Uri? = null
    private var ocrHits: Map<Int, String> = emptyMap()
    private var ocrKey = ""
    private val moments = mutableListOf<Moment>()
    private val exported = mutableListOf<Uri>()
    private var lengthSec = 30

    private lateinit var fileLabel: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var momentsBox: LinearLayout
    private lateinit var keywordsEt: EditText
    private lateinit var ocrCb: CheckBox
    private lateinit var countBar: SeekBar
    private lateinit var countLabel: TextView
    private lateinit var cropBar: SeekBar
    private lateinit var manualEt: EditText
    private lateinit var analyzeBtn: Button
    private lateinit var exportBtn: Button
    private lateinit var shareBtn: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(label("🎬 قصّاص ريلز GTA 6", 22f, true))
        root.addView(label("اختر فيديو اللعبة، وسيجد التطبيق أقوى اللحظات ويحوّلها إلى ريلز عمودية 9:16."))

        fileLabel = label("لم يتم اختيار فيديو")
        root.addView(button("1) اختيار الفيديو") { picker.launch(arrayOf("video/*")) })
        root.addView(fileLabel)

        root.addView(label("طول الريلز", 15f, true))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(15, 30, 45, 60).forEach { s ->
            rg.addView(RadioButton(this).apply {
                text = "$s ث"
                id = View.generateViewId()
                isChecked = s == 30
                setOnClickListener { lengthSec = s }
            })
        }
        root.addView(rg)

        countLabel = label("عدد الريلز: 5", 15f, true)
        root.addView(countLabel)
