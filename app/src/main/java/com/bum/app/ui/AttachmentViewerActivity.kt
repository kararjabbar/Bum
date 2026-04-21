package com.bum.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.databinding.ActivityAttachmentViewerBinding
import com.bum.app.models.Attachment
import com.bum.app.security.AntiTamper
import com.bum.app.security.SecureBytes
import com.bum.app.security.SecureDataManager
import com.bum.app.utils.AutoHideController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

// عارض المرفقات المدمج — يعرض الصور والفيديو والنص داخل التطبيق
class AttachmentViewerActivity : BaseSecureActivity() {

    companion object {
        const val EXTRA_ATT_JSON = "extra_att_json"
        private const val SEEK_STEP_MS = 5_000
        private const val FAST_SPEED = 2.0f
        private const val NORMAL_SPEED = 1.0f
    }

    private lateinit var binding: ActivityAttachmentViewerBinding
    private lateinit var secure: SecureDataManager
    private var current: Attachment? = null

    // بيانات المرفق مشفّرة في الذاكرة
    private var secureBytes: SecureBytes? = null

    private var tempMediaFile: File? = null

    // تحكّم في إخفاء عناصر الواجهة
    private var autoHide: AutoHideController? = null

    // حالة تشغيل الفيديو
    private var videoOverlayActive: Boolean = false
    private var isVideoMode: Boolean = false
    private var mediaPlayer: MediaPlayer? = null
    private var isFastForwarding: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val videoUiHandler = Handler(Looper.getMainLooper())

    @Volatile private var uiReleased: Boolean = false

    private val videoProgressRunnable = object : Runnable {
        override fun run() {
            if (uiReleased) return
            try {
                if (binding.videoView.isPlaying) {
                    val dur = binding.videoView.duration.coerceAtLeast(1)
                    val cur = binding.videoView.currentPosition
                    binding.seekBar.progress = ((cur.toLong() * 1000L) / dur).toInt()
                    binding.tvTimeCurrent.text = fmtMs(cur)
                    binding.tvTimeTotal.text = fmtMs(dur)
                }
            } catch (_: Throwable) { }
            if (!uiReleased) videoUiHandler.postDelayed(this, 400L)
        }
    }

    private var gestureDetector: GestureDetector? = null
    private var activePointerCount: Int = 0

    //  SAF  export
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val sb = secureBytes
        if (sb == null) {
            toast("لا توجد بيانات للتصدير")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                sb.use { raw ->
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(raw); out.flush(); true
                        } ?: false
                    } catch (_: Exception) { false }
                } ?: false
            }
            toast(if (ok) "✓ تم تصدير الملف بأمان" else "فشل التصدير")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityAttachmentViewerBinding.inflate(layoutInflater)
            setContentView(binding.root)
            applyStatusBarPadding(binding.header, extraPaddingDp = 8)
        } catch (_: Throwable) {
            finish(); return
        }

        // فحص خفيف ضد hooking/debug قبل التعامل مع محتوى حسّاس
        val report = try { AntiTamper.quickCheck(this) } catch (_: Throwable) { null }
        if (report != null && (report.debuggerAttached || report.hookingLib || report.traced)) {
            toast("⚠️ بيئة غير موثوقة — إغلاق")
            finish(); return
        }

        secure = SecureDataManager.getInstance(this)

        val json = intent.getStringExtra(EXTRA_ATT_JSON)
        if (json.isNullOrBlank()) { finish(); return }

        current = try {
            Attachment.fromJson(org.json.JSONObject(json))
        } catch (_: Exception) { null }

        val att = current
        if (att == null) {
            toast("تعذّر قراءة المرفق")
            finish(); return
        }

        // ============ PDF: نُحوِّل مباشرة إلى العارض الجديد ============
        val fnameLower = att.filename.lowercase()
        if (att.mimeType.lowercase() == "application/pdf" || fnameLower.endsWith(".pdf")) {
            try {
                val i = Intent(this, PdfPagerActivity::class.java)
                i.putExtra(PdfPagerActivity.EXTRA_ATT_JSON, json)
                startActivity(i)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } catch (_: Throwable) {
                toast("تعذّر فتح ملف PDF")
            }
            finish()
            return
        }

        binding.tvFilename.text = att.filename
        binding.tvMeta.text = "${humanSize(att.sizeBytes)} • ${att.mimeType}"

        binding.btnBack.setOnClickListener { runCatching { finish() } }

        binding.btnExport.setOnClickListener {
            try {
                BumApplication.lifecycleObserver.allowOneExternalRoundTrip()
                exportLauncher.launch(att.filename)
            } catch (_: Throwable) {
                toast("تعذّر بدء التصدير")
            }
        }

        // v1.3: Auto-hide setup مؤقت — سيُعاد بناؤه بعد معرفة نوع الملف
        //   • للفيديو/الصوت: header + tv_badge + videoOverlay
        //   • للصور/النصوص/HTML: header + tv_badge فقط (videoOverlay يبقى GONE)
        autoHide = AutoHideController(
            views = listOf(binding.header, binding.tvBadge),
            hideDelayMs = AutoHideController.DEFAULT_HIDE_MS
        )

        setupGlobalGestureDetector()
        binding.touchSurface.isClickable = false
        binding.touchSurface.isFocusable = false

        loadAndShow(att)
    }

    private fun setupGlobalGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // v1.3: النقر على عنصر تحكّم (header/overlay) = onUserInteraction (لا toggle)
                //       النقر على منطقة المحتوى = toggle
                if (uiReleased) return false
                try {
                    if (isTouchOverControls(e.rawX, e.rawY)) {
                        autoHide?.onUserInteraction()
                    } else {
                        autoHide?.tap()
                    }
                } catch (_: Throwable) {}
                return false  // لا نستهلك — نترك الحدث يصل للـ children
            }

            override fun onLongPress(e: MotionEvent) {
                if (uiReleased) return
                if (!isVideoMode) return
                if (activePointerCount > 1) return
                if (isTouchOverControls(e.rawX, e.rawY)) return
                enableFastForward()
            }
        })
        gestureDetector?.setIsLongpressEnabled(true)
    }

    // هل اللمس فوق منطقة التحكّم؟
    private fun isTouchOverControls(rawX: Float, rawY: Float): Boolean {
        return try {
            val r = Rect()
            // header
            if (binding.header.visibility == View.VISIBLE) {
                binding.header.getGlobalVisibleRect(r)
                if (r.contains(rawX.toInt(), rawY.toInt())) return true
            }
            // video overlay (أزرار + seekbar)
            if (binding.videoOverlay.visibility == View.VISIBLE) {
                binding.videoOverlay.getGlobalVisibleRect(r)
                if (r.contains(rawX.toInt(), rawY.toInt())) return true
            }
            false
        } catch (_: Throwable) { false }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        try {
            // تتبّع عدد الأصابع
            activePointerCount = ev.pointerCount

            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isFastForwarding) {
                        disableFastForward()
                    }
                }
            }

            gestureDetector?.onTouchEvent(ev)
        } catch (_: Throwable) { }
        return try {
            super.dispatchTouchEvent(ev)
        } catch (_: Throwable) {
            true
        }
    }

    private fun loadAndShow(att: Attachment) {
        binding.progress.visibility = View.VISIBLE
        binding.ivImage.visibility = View.GONE
        binding.tvText.visibility = View.GONE
        binding.tvUnsupported.visibility = View.GONE
        binding.videoView.visibility = View.GONE
        binding.webContent.visibility = View.GONE
        binding.videoOverlay.visibility = View.GONE
        try { binding.audioModernView.visibility = View.GONE } catch (_: Throwable) {}

        lifecycleScope.launch {
            val raw: ByteArray? = withContext(Dispatchers.IO) {
                try { secure.decryptAttachmentToBytes(att) } catch (_: Throwable) { null }
            }
            if (uiReleased) return@launch
            binding.progress.visibility = View.GONE

            if (raw == null) {
                binding.tvUnsupported.text = "— تعذّر فك تشفير الملف —"
                binding.tvUnsupported.visibility = View.VISIBLE
                autoHide?.start()
                return@launch
            }

            // انقل raw فوراً إلى SecureBytes (AES in-memory)
            val wrapped = try { SecureBytes.wrap(raw) } catch (_: Throwable) { null }
            if (wrapped == null) {
                binding.tvUnsupported.text = "— تعذّر تحضير الملف —"
                binding.tvUnsupported.visibility = View.VISIBLE
                autoHide?.start()
                return@launch
            }
            secureBytes = wrapped

            val mime = att.mimeType.lowercase()
            val fname = att.filename.lowercase()

            try {
                when {
                    mime.startsWith("image/") || fname.endsWithAny(
                        ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif"
                    ) -> showImageSecure(wrapped)

                    mime.startsWith("video/") || fname.endsWithAny(
                        ".mp4", ".3gp", ".mkv", ".webm", ".avi", ".mov", ".m4v"
                    ) -> showVideoSecure(att, wrapped, isAudio = false)

                    mime.startsWith("audio/") || fname.endsWithAny(
                        ".mp3", ".m4a", ".wav", ".ogg", ".aac", ".flac", ".opus", ".amr"
                    ) -> showVideoSecure(att, wrapped, isAudio = true)

                    mime == "text/html" || mime == "image/svg+xml" ||
                        fname.endsWithAny(".html", ".htm", ".svg") -> showHtmlSecure(wrapped, mime)

                    mime.startsWith("text/") ||
                        mime == "application/json" || mime == "application/xml" ||
                        fname.endsWithAny(
                            ".txt", ".md", ".json", ".xml", ".csv", ".log", ".ini",
                            ".conf", ".yml", ".yaml", ".java", ".kt", ".py", ".js",
                            ".ts", ".c", ".cpp", ".h", ".cs", ".rb", ".go", ".rs",
                            ".sh", ".bat", ".sql"
                        ) -> showTextSecure(wrapped)

                    else -> showHexSecure(att, wrapped)
                }
            } catch (_: Throwable) {
                try {
                    binding.tvUnsupported.text = "— تعذّر عرض الملف —"
                    binding.tvUnsupported.visibility = View.VISIBLE
                } catch (_: Throwable) {}
            }

            // بدء الـ autoHide بعد تحضير العرض
            try { autoHide?.start() } catch (_: Throwable) {}
        }
    }

    private fun showImageSecure(sb: SecureBytes) {
        val size: IntArray? = sb.use { bytes ->
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                intArrayOf(opts.outWidth.coerceAtLeast(1), opts.outHeight.coerceAtLeast(1))
            } catch (_: Throwable) { null }
        }
        val displayW = resources.displayMetrics.widthPixels
        val displayH = resources.displayMetrics.heightPixels
        val sampleSize = if (size != null) {
            computeInSampleSize(size[0], size[1], displayW * 2, displayH * 2)
        } else 1

        val bmp: Bitmap? = sb.use { bytes ->
            decodeSafe(bytes, sampleSize)
        }

        if (bmp != null && !bmp.isRecycled) {
            try {
                binding.ivImage.setImageBitmap(bmp)
                binding.ivImage.scaleType = ImageView.ScaleType.MATRIX
                binding.ivImage.visibility = View.VISIBLE
                attachZoomPan(binding.ivImage)
            } catch (_: Throwable) {
                binding.tvUnsupported.text = "— تعذّر عرض الصورة —"
                binding.tvUnsupported.visibility = View.VISIBLE
            }
        } else {
            binding.tvUnsupported.text = "— تعذّر عرض الصورة —"
            binding.tvUnsupported.visibility = View.VISIBLE
        }
    }

    private fun decodeSafe(bytes: ByteArray, initialSample: Int): Bitmap? {
        var sample = max(1, initialSample)
        repeat(4) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                }
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (b != null) return b
            } catch (_: OutOfMemoryError) {
                sample *= 2
            } catch (_: Throwable) {
                return null
            }
            sample *= 2
        }
        // محاولة أخيرة بـ RGB_565
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sample
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) { null }
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        var w = srcW; var h = srcH
        while (w / 2 >= reqW && h / 2 >= reqH) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    private fun showVideoSecure(att: Attachment, sb: SecureBytes, isAudio: Boolean) {
        isVideoMode = !isAudio
        val tmp = sb.use { raw -> writeToPrivateCache(raw, att.filename) } ?: run {
            binding.tvUnsupported.text = "— تعذّر تحضير الملف —"
            binding.tvUnsupported.visibility = View.VISIBLE
            return
        }
        tempMediaFile = tmp


        try { autoHide?.release() } catch (_: Throwable) {}
        autoHide = AutoHideController(
            views = listOf(binding.header, binding.tvBadge, binding.videoOverlay),
            hideDelayMs = AutoHideController.DEFAULT_HIDE_MS
        )

        try {
            binding.videoView.setVideoURI(Uri.fromFile(tmp))
            binding.videoView.setOnPreparedListener { mp: MediaPlayer ->
                mediaPlayer = mp
                runCatching { mp.isLooping = false }
                runCatching { binding.videoView.start() }
                setupVideoOverlay(isAudio)
                if (isAudio) startAudioVisualizerAnimation()
            }
            binding.videoView.setOnErrorListener { _, _, _ ->
                runCatching {
                    binding.videoView.visibility = View.GONE
                    binding.videoOverlay.visibility = View.GONE
                    try { binding.audioModernView.visibility = View.GONE } catch (_: Throwable) {}
                    binding.tvUnsupported.text = "— لا يمكن تشغيل هذا الملف —"
                    binding.tvUnsupported.visibility = View.VISIBLE
                }
                true
            }
            binding.videoView.setOnCompletionListener {
                runCatching {
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                    autoHide?.show(animate = true)
                    if (isAudio) stopAudioVisualizerAnimation()
                }
            }

            if (isAudio) {
                try {
                    binding.videoView.visibility = View.VISIBLE
                    binding.audioModernView.visibility = View.VISIBLE
                    binding.tvAudioTitle.text = att.filename
                    binding.tvAudioMeta.text = "${humanSize(att.sizeBytes)} • ${att.mimeType}"
                    binding.audioModernView.bringToFront()
                    binding.videoOverlay.bringToFront()
                    binding.header.bringToFront()
                    binding.tvBadge.bringToFront()
                } catch (_: Throwable) {}
            } else {
                binding.videoView.visibility = View.VISIBLE
            }
        } catch (_: Throwable) {
            binding.tvUnsupported.text = "— تعذّر تشغيل الملف —"
            binding.tvUnsupported.visibility = View.VISIBLE
        }
    }

    // مشغّل الصوت مع رسوم متحركة
    private var audioSpinAnimator: android.animation.ValueAnimator? = null
    private var audioBarsHandler: Handler? = null

    private var audioBarLevels: FloatArray = FloatArray(21)

    @Volatile private var audioEnergyAvg: Float = 0f


    private fun getAudioBars(): List<View> = try {
        listOf(
            binding.audioBar1, binding.audioBar2, binding.audioBar3,
            binding.audioBar4, binding.audioBar5, binding.audioBar6,
            binding.audioBar7, binding.audioBar8, binding.audioBar9,
            binding.audioBar10, binding.audioBar11, binding.audioBar12,
            binding.audioBar13, binding.audioBar14, binding.audioBar15,
            binding.audioBar16, binding.audioBar17, binding.audioBar18,
            binding.audioBar19, binding.audioBar20, binding.audioBar21
        )
    } catch (_: Throwable) { emptyList() }


    private val audioBarsRunnable = object : Runnable {
        override fun run() {
            if (uiReleased) return
            try {
                val density = resources.displayMetrics.density
                val minDp = 6f
                val maxDp = 74f
                val playing = try { binding.videoView.isPlaying } catch (_: Throwable) { false }
                val bars = getAudioBars()
                if (bars.isNotEmpty()) {
                    updateMathematicalLevels(bars.size, playing)
                    val levels = audioBarLevels
                    var sum = 0f
                    for (i in bars.indices) {
                        val lv = (if (i < levels.size) levels[i] else 0f).coerceIn(0f, 1f)
                        sum += lv
                        val targetDp = minDp + (maxDp - minDp) * lv
                        val targetPx = (targetDp * density).toInt().coerceAtLeast((minDp * density).toInt())
                        val bar = bars[i]
                        val lp = bar.layoutParams
                        if (lp.height != targetPx) {
                            lp.height = targetPx
                            bar.layoutParams = lp
                        }
                    }
                    audioEnergyAvg = (sum / bars.size).coerceIn(0f, 1f)
                    try {
                        val sc = 1f + audioEnergyAvg * 0.08f
                        binding.audioDisc.scaleX = sc
                        binding.audioDisc.scaleY = sc
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            // معدّل تحديث أسرع (~33ms ≈ 30 fps) لحركة أكثر سيولةً
            audioBarsHandler?.postDelayed(this, 33L)
        }
    }

    // حساب ارتفاعات أعمدة ميزان الصوت
    private fun updateMathematicalLevels(n: Int, playing: Boolean) {
        if (!playing) {
            for (i in 0 until audioBarLevels.size) {
                audioBarLevels[i] = (audioBarLevels[i] * 0.82f).coerceAtLeast(0f)
            }
            return
        }
        val pos = try { (mediaPlayer?.currentPosition ?: 0).toFloat() } catch (_: Throwable) { 0f }
        val t = pos / 1000f
        val beat = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(t * Math.PI * 2.2).toFloat())
        for (i in 0 until audioBarLevels.size) {
            val c = i.toFloat() / kotlin.math.max(1, n - 1).toFloat()
            val envelope = kotlin.math.sin(c * Math.PI).toFloat()
            val w1 = kotlin.math.sin((t * 3.1f + c * 6.2f).toDouble()).toFloat()
            val w2 = kotlin.math.sin((t * 5.7f + c * 9.4f + 1.3f).toDouble()).toFloat()
            val w3 = kotlin.math.sin((t * 1.9f + c * 3.3f + 0.7f).toDouble()).toFloat()
            val wave = (w1 * 0.45f + w2 * 0.30f + w3 * 0.25f) * 0.5f + 0.5f // 0..1
            val target = (wave * beat * (0.35f + 0.65f * envelope)).coerceIn(0f, 1f)
            val prev = audioBarLevels[i]
            val smoothed = if (target > prev) prev + (target - prev) * 0.55f
                           else prev + (target - prev) * 0.22f
            audioBarLevels[i] = smoothed
        }
    }

    private fun startAudioVisualizerAnimation() {
        try {
            // قرص الصوت يدور — الآن بسرعة متغيّرة تتأثّر بالطاقة الصوتية
            audioSpinAnimator?.cancel()
            val disc = binding.audioDisc
            val anim = android.animation.ValueAnimator.ofFloat(0f, 360f)
            anim.duration = 9000L
            anim.repeatCount = android.animation.ValueAnimator.INFINITE
            anim.interpolator = android.view.animation.LinearInterpolator()
            anim.addUpdateListener { va ->
                try {
                    val base = va.animatedValue as Float
                    // تسريع طفيف حسب الطاقة (يعطي انطباع التزامن)
                    disc.rotation = base + audioEnergyAvg * 6f
                } catch (_: Throwable) {}
            }
            anim.start()
            audioSpinAnimator = anim

            // أعمدة ميزان الصوت مع تحديث دائم
            audioBarsHandler?.removeCallbacks(audioBarsRunnable)
            audioBarsHandler = Handler(Looper.getMainLooper())
            audioBarsHandler?.post(audioBarsRunnable)
        } catch (_: Throwable) {}
    }

    private fun stopAudioVisualizerAnimation() {
        try {
            audioSpinAnimator?.cancel()
            audioSpinAnimator = null
            audioBarsHandler?.removeCallbacks(audioBarsRunnable)
            audioBarsHandler = null
            // إعادة تصفير المستويات
            for (i in audioBarLevels.indices) audioBarLevels[i] = 0f
            audioEnergyAvg = 0f
            try {
                binding.audioDisc.scaleX = 1f
                binding.audioDisc.scaleY = 1f
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    // إعداد overlay الفيديو
    @SuppressLint("ClickableViewAccessibility")
    private fun setupVideoOverlay(@Suppress("UNUSED_PARAMETER") isAudio: Boolean) {
        if (videoOverlayActive) return
        videoOverlayActive = true

        try {
            binding.videoOverlay.visibility = View.VISIBLE
            binding.videoOverlay.alpha = 1f
            autoHide?.show(animate = false)

            // زر Play/Pause
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            binding.btnPlayPause.setOnClickListener {
                try {
                    if (binding.videoView.isPlaying) {
                        binding.videoView.pause()
                        binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                    } else {
                        binding.videoView.start()
                        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                    }
                    autoHide?.onUserInteraction()
                } catch (_: Throwable) {}
            }

            // زر إرجاع 5 ثواني
            binding.btnRewind5.setOnClickListener {
                try {
                    val cur = binding.videoView.currentPosition
                    val target = (cur - SEEK_STEP_MS).coerceAtLeast(0)
                    binding.videoView.seekTo(target)
                    autoHide?.onUserInteraction()
                } catch (_: Throwable) {}
            }

            // زر تقديم 5 ثواني
            binding.btnForward5.setOnClickListener {
                try {
                    val dur = binding.videoView.duration.coerceAtLeast(1)
                    val cur = binding.videoView.currentPosition
                    val target = (cur + SEEK_STEP_MS).coerceAtMost(dur - 200)
                    binding.videoView.seekTo(target)
                    autoHide?.onUserInteraction()
                } catch (_: Throwable) {}
            }

            // SeekBar
            binding.seekBar.max = 1000
            binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        try {
                            val dur = binding.videoView.duration.coerceAtLeast(1)
                            val target = ((progress.toLong() * dur) / 1000L).toInt()
                            binding.tvTimeCurrent.text = fmtMs(target)
                        } catch (_: Throwable) {}
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    autoHide?.show(animate = true)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    try {
                        val dur = binding.videoView.duration.coerceAtLeast(1)
                        val target = ((binding.seekBar.progress.toLong() * dur) / 1000L).toInt()
                        binding.videoView.seekTo(target)
                    } catch (_: Throwable) {}
                    autoHide?.onUserInteraction()
                }
            })

            // بدء مؤشر التقدّم
            videoUiHandler.removeCallbacks(videoProgressRunnable)
            if (!uiReleased) videoUiHandler.post(videoProgressRunnable)
        } catch (_: Throwable) { }
    }

    // تفعيل تسريع الفيديو ×2
    private fun enableFastForward() {
        if (isFastForwarding) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val mp = mediaPlayer ?: return

        try {
            if (!binding.videoView.isPlaying) {
                try { binding.videoView.start() } catch (_: Throwable) {}
            }

            val params = PlaybackParams().apply {
                speed = FAST_SPEED
            }
            mp.playbackParams = params

            isFastForwarding = true
            binding.tvSpeedBadge.visibility = View.VISIBLE
            binding.tvSpeedBadge.alpha = 1f
            try {
                val vib = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") vib?.vibrate(30)
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {
            isFastForwarding = false
            try { binding.tvSpeedBadge.visibility = View.GONE } catch (_: Throwable) {}
        }
    }

    // إلغاء تسريع الفيديو والعودة للسرعة العادية
    private fun disableFastForward() {
        if (!isFastForwarding) return
        isFastForwarding = false
        try { binding.tvSpeedBadge.visibility = View.GONE } catch (_: Throwable) {}

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val mp = mediaPlayer ?: return

        try {
            val params = PlaybackParams().apply { speed = NORMAL_SPEED }
            mp.playbackParams = params
        } catch (_: Throwable) {}
    }

    private fun showHtmlSecure(sb: SecureBytes, mime: String) {
        val txt = sb.use { raw -> runCatching { String(raw, Charsets.UTF_8) }.getOrNull() }
        if (txt == null) {
            binding.tvUnsupported.text = "— تعذّر عرض HTML/SVG —"
            binding.tvUnsupported.visibility = View.VISIBLE
            return
        }
        configureWebView()
        val finalMime = if (mime.isBlank()) "text/html" else mime
        try {
            binding.webContent.loadDataWithBaseURL(null, txt, finalMime, "utf-8", null)
            binding.webContent.visibility = View.VISIBLE
        } catch (_: Throwable) {
            binding.tvUnsupported.text = "— تعذّر عرض HTML —"
            binding.tvUnsupported.visibility = View.VISIBLE
        }
    }

    private fun configureWebView() {
        val wv: WebView = binding.webContent
        try {
            wv.setBackgroundColor(0xFF0A0A0F.toInt())
            wv.settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                defaultTextEncodingName = "utf-8"
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
        } catch (_: Throwable) {}
    }

    private fun showTextSecure(sb: SecureBytes) {
        val txt = sb.use { raw -> runCatching { String(raw, Charsets.UTF_8) }.getOrNull() }
        if (txt == null) {
            binding.tvUnsupported.text = "— تعذّر قراءة النص —"
            binding.tvUnsupported.visibility = View.VISIBLE
            return
        }
        binding.tvText.text = txt
        binding.tvText.visibility = View.VISIBLE
    }

    private fun showHexSecure(att: Attachment, sb: SecureBytes) {
        val text = sb.use { bytes ->
            val out = StringBuilder()
            out.append("ℹ️  نوع غير معروف للعرض المباشر\n")
            out.append("الاسم: ${att.filename}\n")
            out.append("النوع: ${att.mimeType}\n")
            out.append("الحجم: ${humanSize(att.sizeBytes)}\n\n")
            out.append("— معاينة HEX (أول 512 بايت) —\n\n")
            val limit = minOf(bytes.size, 512)
            var i = 0
            while (i < limit) {
                out.append("%08X  ".format(i))
                val lineEnd = minOf(i + 16, limit)
                for (j in i until lineEnd) out.append("%02X ".format(bytes[j]))
                for (j in lineEnd until i + 16) out.append("   ")
                out.append(" ")
                for (j in i until lineEnd) {
                    val c = bytes[j].toInt() and 0xFF
                    out.append(if (c in 0x20..0x7E) c.toChar() else '·')
                }
                out.append('\n')
                i += 16
            }
            out.append("\n— يمكنك تصدير الملف لفتحه بتطبيق خارجي —")
            out.toString()
        } ?: "— فشل فك التشفير —"

        binding.tvText.text = text
        binding.tvText.visibility = View.VISIBLE
    }

    private fun writeToPrivateCache(bytes: ByteArray, filename: String): File? {
        return try {
            val dir = File(cacheDir, "media_preview").apply { mkdirs() }
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(dir, "tmp_$safe")
            FileOutputStream(out).use { it.write(bytes); it.flush() }
            out
        } catch (_: Throwable) { null }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachZoomPan(iv: ImageView) {
        val matrix = Matrix()
        val savedMatrix = Matrix()
        val start = PointF()
        var mode = 0
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        iv.post {
            try {
                val d = iv.drawable ?: return@post
                val viewW = iv.width.toFloat()
                val viewH = iv.height.toFloat()
                val bmpW = d.intrinsicWidth.toFloat()
                val bmpH = d.intrinsicHeight.toFloat()
                if (viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) return@post
                val scale = minOf(viewW / bmpW, viewH / bmpH)
                matrix.setScale(scale, scale)
                val dx = (viewW - bmpW * scale) / 2f
                val dy = (viewH - bmpH * scale) / 2f
                matrix.postTranslate(dx, dy)
                iv.imageMatrix = matrix
            } catch (_: Throwable) {}
        }

        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    try {
                        val f = d.scaleFactor
                        matrix.postScale(f, f, d.focusX, d.focusY)
                        iv.imageMatrix = matrix
                    } catch (_: Throwable) {}
                    return true
                }
            })

        iv.setOnTouchListener { _, event ->
            try {
                scaleDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        savedMatrix.set(matrix)
                        start.set(event.x, event.y)
                        mode = 1
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { mode = 2 }
                    MotionEvent.ACTION_MOVE -> {
                        if (mode == 1 && !scaleDetector.isInProgress) {
                            val dx = event.x - start.x
                            val dy = event.y - start.y
                            if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                                matrix.set(savedMatrix)
                                matrix.postTranslate(dx, dy)
                                iv.imageMatrix = matrix
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { mode = 0 }
                }
            } catch (_: Throwable) {}
                    false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val c: WindowInsetsController? = window.insetsController
                c?.hide(WindowInsets.Type.systemBars())
                c?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
            }
        } catch (_: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        if (BumApplication.lifecycleObserver.sessionLocked) {
            startActivity(Intent(this, BiometricActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        try { if (binding.videoView.isPlaying) binding.videoView.pause() } catch (_: Throwable) {}
        try { binding.btnPlayPause.setImageResource(R.drawable.ic_play) } catch (_: Throwable) {}
        disableFastForward()
    }

    override fun onDestroy() {
        uiReleased = true
        try { super.onDestroy() } catch (_: Throwable) {}

        try { autoHide?.release() } catch (_: Throwable) {}
        autoHide = null
        try { stopAudioVisualizerAnimation() } catch (_: Throwable) {}
        try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
        try { videoUiHandler.removeCallbacksAndMessages(null) } catch (_: Throwable) {}

        try { binding.ivImage.setOnTouchListener(null) } catch (_: Throwable) {}
        try { binding.ivImage.setImageDrawable(null) } catch (_: Throwable) {}
        try { binding.videoView.stopPlayback() } catch (_: Throwable) {}
        mediaPlayer = null
        try {
            binding.webContent.loadUrl("about:blank")
            binding.webContent.clearHistory()
            binding.webContent.clearCache(true)
        } catch (_: Throwable) {}
        try { tempMediaFile?.delete() } catch (_: Throwable) {}
        try { File(cacheDir, "media_preview").deleteRecursively() } catch (_: Throwable) {}
        tempMediaFile = null

        try { secureBytes?.wipe() } catch (_: Throwable) {}
        secureBytes = null

        gestureDetector = null
        System.gc()
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }

    private fun fmtMs(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    private fun String.endsWithAny(vararg suffixes: String): Boolean =
        suffixes.any { this.endsWith(it, ignoreCase = true) }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }
}
