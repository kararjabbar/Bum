package com.bum.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.models.Attachment
import com.bum.app.security.AntiTamper
import com.bum.app.security.SecureBytes
import com.bum.app.security.SecureDataManager
import com.bum.app.utils.AutoHideController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// عارض PDF أصلي مع تمرير متواصل وتكبير
class PdfPagerActivity : BaseSecureActivity() {

    companion object {
        const val EXTRA_ATT_JSON = "extra_att_json"
        private const val PAGE_GAP_DP = 8
    }

    private lateinit var secure: SecureDataManager
    private var current: Attachment? = null

    private var secureBytes: SecureBytes? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount: Int = 0

    private val pageSizes = mutableListOf<Pair<Int, Int>>()

    private lateinit var bitmapCache: LruCache<Int, Bitmap>

    private lateinit var root: FrameLayout
    private lateinit var recycler: RecyclerView
    private lateinit var header: LinearLayout
    private lateinit var tvFilename: TextView
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnExport: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var errorMessage: TextView

    private var autoHide: AutoHideController? = null

    @Volatile private var uiReleased: Boolean = false

    private var globalGestureDetector: GestureDetector? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val sb = secureBytes ?: run {
            toast("لا توجد بيانات للتصدير"); return@registerForActivityResult
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

        val report = try { AntiTamper.quickCheck(this) } catch (_: Throwable) { null }
        if (report != null && (report.debuggerAttached || report.hookingLib || report.traced)) {
            toast("⚠️ بيئة غير موثوقة — إغلاق")
            finish(); return
        }

        try { buildLayout() } catch (_: Throwable) { finish(); return }
        setContentView(root)

        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        bitmapCache = object : LruCache<Int, Bitmap>(maxKb / 6) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
            override fun entryRemoved(
                evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?
            ) {
                if (evicted && !oldValue.isRecycled) {
                    try { oldValue.recycle() } catch (_: Throwable) {}
                }
            }
        }

        secure = SecureDataManager.getInstance(this)

        val json = intent.getStringExtra(EXTRA_ATT_JSON)
        if (json.isNullOrBlank()) { finish(); return }
        current = try {
            Attachment.fromJson(org.json.JSONObject(json))
        } catch (_: Exception) { null }

        val att = current
        if (att == null) { toast("تعذّر قراءة المرفق"); finish(); return }

        tvFilename.text = att.filename
        btnBack.setOnClickListener { runCatching { finish() } }
        btnExport.setOnClickListener {
            try {
                BumApplication.lifecycleObserver.allowOneExternalRoundTrip()
                exportLauncher.launch(att.filename)
            } catch (_: Throwable) { toast("تعذّر بدء التصدير") }
        }

        autoHide = AutoHideController(
            views = listOf(header, tvPageIndicator),
            hideDelayMs = AutoHideController.DEFAULT_HIDE_MS
        )

        setupGlobalGestureDetector()

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (uiReleased) return
                updatePageIndicator()
                if (dy != 0) try { autoHide?.onUserInteraction() } catch (_: Throwable) {}
            }
        })

        autoHide?.start()

        loadPdf(att)
    }

    private fun setupGlobalGestureDetector() {
        globalGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // يعمل فقط إذا كان اللمس على overlay (header/indicator)
                if (uiReleased) return false
                try {
                    val x = e.rawX.toInt()
                    val y = e.rawY.toInt()
                    val r = android.graphics.Rect()
                    if (header.visibility == View.VISIBLE) {
                        header.getGlobalVisibleRect(r)
                        if (r.contains(x, y)) { autoHide?.tap(); return true }
                    }
                    if (tvPageIndicator.visibility == View.VISIBLE) {
                        tvPageIndicator.getGlobalVisibleRect(r)
                        if (r.contains(x, y)) { autoHide?.tap(); return true }
                    }
                } catch (_: Throwable) {}
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            try { globalGestureDetector?.onTouchEvent(ev) } catch (_: Throwable) {}
        }
        return try {
            super.dispatchTouchEvent(ev)
        } catch (_: Throwable) { true }
    }

    private fun buildLayout() {
        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
        }

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfPagerActivity, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(false)
            setItemViewCacheSize(2)
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipToPadding = false
            setPadding(0, dp(60), 0, dp(60))
        }
        root.addView(recycler)

        progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.CENTER
            )
        }
        root.addView(progress)

        errorMessage = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            textSize = 14f
            visibility = View.GONE
        }
        root.addView(errorMessage)

        // Header
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(40), dp(14), dp(12))
            setBackgroundColor(Color.parseColor("#CC000000"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        btnBack = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setBackgroundResource(R.drawable.bg_icon_btn)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        header.addView(btnBack)

        tvFilename = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvFilename)

        btnExport = ImageButton(this).apply {
            setImageResource(R.drawable.ic_download)
            setBackgroundResource(R.drawable.bg_icon_btn)
            setColorFilter(Color.parseColor("#FFB86B"))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        header.addView(btnExport)

        root.addView(header)

        // مؤشر الصفحة (أسفل)
        tvPageIndicator = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            textSize = 12f
            text = "…"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(18) }
        }
        root.addView(tvPageIndicator)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadPdf(att: Attachment) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val prep = withContext(Dispatchers.IO) {
                prepareRenderer(att)
            }
            if (uiReleased) return@launch
            progress.visibility = View.GONE

            if (prep == null) {
                errorMessage.text = "— تعذّر فتح ملف PDF —\nربما الملف تالف أو مشفّر بكلمة مرور."
                errorMessage.visibility = View.VISIBLE
                return@launch
            }
            renderer = prep
            pageCount = prep.pageCount

            pageSizes.clear()
            withContext(Dispatchers.IO) {
                try {
                    synchronized(prep) {
                        for (i in 0 until pageCount) {
                            try {
                                val p = prep.openPage(i)
                                pageSizes.add(Pair(p.width, p.height))
                                p.close()
                            } catch (_: Throwable) {
                                pageSizes.add(Pair(612, 792))
                            }
                        }
                    }
                } catch (_: Throwable) {
                    for (i in 0 until pageCount) pageSizes.add(Pair(612, 792))
                }
            }

            if (uiReleased) return@launch
            tvPageIndicator.text = "1 / $pageCount"
            recycler.adapter = PdfPageAdapter()
        }
    }

    private fun prepareRenderer(att: Attachment): PdfRenderer? {
        return try {
            val raw = secure.decryptAttachmentToBytes(att) ?: return null
            secureBytes = SecureBytes.wrap(raw.copyOf())
            SecureBytes.scrub(raw)

            val dir = File(cacheDir, "pdf_ephemeral").apply { mkdirs() }
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val tmp = File(dir, "p_${System.nanoTime()}.tmp")

            val plain = secureBytes?.borrow() ?: return null
            try {
                FileOutputStream(tmp).use { it.write(plain); it.flush() }
            } finally {
                SecureBytes.scrub(plain)
            }

            val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            try { tmp.delete() } catch (_: Throwable) {}

            pfd = fd
            PdfRenderer(fd)
        } catch (_: Throwable) {
            null
        }
    }

    private inner class PdfPageAdapter : RecyclerView.Adapter<PdfPageVH>() {

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageVH {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                    setBackgroundColor(Color.WHITE)
                val gap = dp(PAGE_GAP_DP)
                setPadding(0, gap, 0, gap)
            }
            val iv = ImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
                adjustViewBounds = true
            }
            container.addView(iv)

            val spinner = ProgressBar(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dp(28), dp(28), Gravity.CENTER
                )
            }
            container.addView(spinner)

            return PdfPageVH(container, iv, spinner)
        }

        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: PdfPageVH, position: Int) {
            if (uiReleased) return
            val size = pageSizes.getOrNull(position) ?: Pair(612, 792)
            val screenW = resources.displayMetrics.widthPixels
            val targetW = screenW
            val ratio = size.second.toFloat() / size.first.toFloat().coerceAtLeast(1f)
            val targetH = (targetW * ratio).toInt().coerceAtLeast(dp(200))

            val lp = holder.iv.layoutParams
            lp.width = targetW
            lp.height = targetH
            holder.iv.layoutParams = lp

            val cached = bitmapCache.get(position)
            if (cached != null && !cached.isRecycled) {
                holder.iv.setImageBitmap(cached)
                holder.spinner.visibility = View.GONE
                return
            }
            holder.iv.setImageBitmap(null)
            holder.spinner.visibility = View.VISIBLE
            holder.itemView.post {
                renderPageAsync(position, holder)
            }
        }

        override fun onViewRecycled(holder: PdfPageVH) {
            super.onViewRecycled(holder)
            try { holder.iv.setImageBitmap(null) } catch (_: Throwable) {}
        }
    }

    private class PdfPageVH(
        container: View, val iv: ImageView, val spinner: ProgressBar
    ) : RecyclerView.ViewHolder(container)

    private fun renderPageAsync(index: Int, holder: PdfPageVH) {
        val r = renderer ?: return
        if (uiReleased) return
        val targetW = resources.displayMetrics.widthPixels.coerceAtLeast(dp(360))
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    synchronized(r) {
                        if (uiReleased) return@withContext null
                        val page = r.openPage(index)
                        val ratio = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                        val w = targetW
                        val h = (w * ratio).toInt().coerceAtLeast(1)
                        val bitmap = try {
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        } catch (_: OutOfMemoryError) {
                            try {
                                System.gc()
                                Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                            } catch (_: Throwable) { null }
                        }
                        if (bitmap == null) {
                            try { page.close() } catch (_: Throwable) {}
                            return@withContext null
                        }
                        bitmap.eraseColor(Color.WHITE)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } catch (_: Throwable) {
                            try { bitmap.recycle() } catch (_: Throwable) {}
                            try { page.close() } catch (_: Throwable) {}
                            return@withContext null
                        }
                        try { page.close() } catch (_: Throwable) {}
                        bitmap
                    }
                } catch (_: Throwable) { null }
            }
            if (uiReleased) {
                bmp?.let { try { if (!it.isRecycled) it.recycle() } catch (_: Throwable) {} }
                return@launch
            }
            if (bmp != null) {
                try { bitmapCache.put(index, bmp) } catch (_: Throwable) {}
                try {
                    if (holder.bindingAdapterPosition == index) {
                        holder.iv.setImageBitmap(bmp)
                        holder.spinner.visibility = View.GONE
                    }
                } catch (_: Throwable) {}
            } else {
                try {
                    if (holder.bindingAdapterPosition == index) {
                        holder.spinner.visibility = View.GONE
                    }
                } catch (_: Throwable) {}
            }
        }
    }


    private fun updatePageIndicator() {
        try {
            val lm = recycler.layoutManager as? LinearLayoutManager ?: return
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first < 0) return
            val current = if (last > first) {
                val firstView = lm.findViewByPosition(first)
                if (firstView != null) {
                    val top = firstView.top
                    val bottom = firstView.bottom
                    val screenH = recycler.height
                    val visibleRatio = (minOf(bottom, screenH) - maxOf(top, 0)).toFloat() / screenH
                    if (visibleRatio < 0.45f && last > first) first + 1 else first
                } else first
            } else first
            tvPageIndicator.text = "${current + 1} / $pageCount"
        } catch (_: Throwable) {}
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

    override fun onDestroy() {
        uiReleased = true
        try { super.onDestroy() } catch (_: Throwable) {}

        try { autoHide?.release() } catch (_: Throwable) {}
        autoHide = null
        try { renderer?.close() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        renderer = null
        pfd = null
        try { secureBytes?.wipe() } catch (_: Throwable) {}
        secureBytes = null
        if (::bitmapCache.isInitialized) {
            try {
                val snap = bitmapCache.snapshot()
                for ((_, b) in snap) {
                    try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
                }
                bitmapCache.evictAll()
            } catch (_: Throwable) {}
        }
        try {
            File(cacheDir, "pdf_ephemeral").listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {}
        globalGestureDetector = null
        System.gc()
    }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }
}
