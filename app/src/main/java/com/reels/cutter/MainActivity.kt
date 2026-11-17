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
        root.addView(countLabel)        countBar = SeekBar(this).apply {
            max = 9
            progress = 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    countLabel.text = "عدد الريلز: ${p + 1}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(countBar)

        root.addView(label("موضع الاقتصاص الأفقي (يسار ← يمين)", 15f, true))
        cropBar = SeekBar(this).apply { max = 100; progress = 50 }
        root.addView(cropBar)

        root.addView(label("بحث عن كلمات تظهر على الشاشة", 15f, true))
        keywordsEt = EditText(this).apply {
            hint = "WASTED, BUSTED, MISSION PASSED"
            setText("WASTED, BUSTED, MISSION PASSED")
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(keywordsEt)
        ocrCb = CheckBox(this).apply { text = "تفعيل البحث بالنص (أبطأ لكن أدق)" }
        root.addView(ocrCb)

        analyzeBtn = button("2) تحليل الفيديو وإيجاد اللحظات") { analyze() }
        root.addView(analyzeBtn)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        status = label("")
        root.addView(status)

        root.addView(label("اللحظات المقترحة", 17f, true))
        momentsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(momentsBox)

        root.addView(label("إضافة لحظة يدويًا (مثال 12:45)", 15f, true))
        val manualRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualEt = EditText(this).apply {
            hint = "mm:ss"
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        manualRow.addView(manualEt)
        manualRow.addView(button("إضافة") { addManual() })
        root.addView(manualRow)

        exportBtn = button("3) تصدير الريلز المحددة") { doExport() }
        root.addView(exportBtn)
        shareBtn = button("4) مشاركة الريلز (انستغرام / تيك توك / يوتيوب...)") { share() }
        root.addView(shareBtn)
        root.addView(button("نسخ وصف + هاشتاغات") { copyCaption() })
        root.addView(label("تُحفظ الريلز في: Movies/GTAReels"))
    }

    private fun onVideoPicked(uri: Uri) {
        try {
            val (d, w, h) = Analyzer.info(this, uri)
            videoUri = uri
            durationMs = d
            vw = w
            vh = h
            cachedUri = null
            audio = null
            motion = null
            moments.clear()
            exported.clear()
            momentsBox.removeAllViews()
            fileLabel.text = "المدة: ${fmt(d)}  |  الدقة: ${w}x$h"
            status.text = ""
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح الفيديو: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setBusy(b: Boolean) {
        analyzeBtn.isEnabled = !b
        exportBtn.isEnabled = !b
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateProgress(p: Int) = runOnUiThread { progress.progress = p }
    private fun updateStatus(s: String) = runOnUiThread { status.text = s }
