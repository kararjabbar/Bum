package com.bum.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.databinding.ActivityMainBinding
import com.bum.app.models.Note
import com.bum.app.security.ScreenshotBlocker
import com.bum.app.security.SecureDataManager
import com.bum.app.utils.AppSettings
import com.bum.app.utils.DateUtils
import com.bum.app.utils.ThemeGlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// الواجهة الرئيسية
class MainActivity : BaseSecureActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var secure: SecureDataManager
    private lateinit var settings: AppSettings
    private lateinit var adapter: NoteAdapter
    private var tickerJob: Job? = null

    // آخر نص بحث
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarPadding(binding.topHeader, extraPaddingDp = 6)

        secure = SecureDataManager.getInstance(this)
        settings = AppSettings.getInstance(this)

        ThemeGlow.applyAccentGlow(binding.viewGlowBg, this)

        setupUI()
        setupList()
        setupSearch()
        animateEntrance()
    }

    override fun onResume() {
        super.onResume()

        if (BumApplication.lifecycleObserver.sessionLocked) {
            startActivity(Intent(this, BiometricActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }

        secure.purgeExpiredNotes()
        refresh()
        startTicker()
    }

    override fun onPause() {
        super.onPause()
        tickerJob?.cancel()
    }

    private fun setupUI() {
        binding.tvDate.text = DateUtils.getArabicDate()
        binding.tvGreeting.text = DateUtils.getTimeGreeting()

        binding.btnWrite.setOnClickListener {
            val bounce = AnimationUtils.loadAnimation(this, R.anim.scale_bounce)
            binding.btnWrite.startAnimation(bounce)
            lifecycleScope.launch {
                delay(80)
                startActivity(Intent(this@MainActivity, WriteActivity::class.java))
                overridePendingTransition(R.anim.slide_up_fade, android.R.anim.fade_out)
            }
        }

        binding.btnVault.setOnClickListener {
            requireAuthThen {
                startActivity(Intent(this, VaultActivity::class.java))
                overridePendingTransition(R.anim.slide_up_fade, android.R.anim.fade_out)
            }
        }

        binding.btnSecretCompartment.setOnClickListener {
            requireAuthThen {
                startActivity(Intent(this, EncryptedNotesActivity::class.java))
                overridePendingTransition(R.anim.slide_up_fade, android.R.anim.fade_out)
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_up_fade, android.R.anim.fade_out)
        }

        binding.tvAppPhilosophy.setOnClickListener { showPhilosophyDialog() }
    }

    private fun setupList() {
        adapter = NoteAdapter(
            items = mutableListOf(),
            secure = secure,
            onOpen = { note -> openNote(note) },
            onDelete = { confirmDelete(it) }
        )
        binding.rvNotes.layoutManager = LinearLayoutManager(this)
        binding.rvNotes.adapter = adapter
    }


    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility =
                    if (currentQuery.isEmpty()) View.GONE else View.VISIBLE
                refresh()
            }
        })
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText("")
        }
    }



    private fun requireAuthThen(action: () -> Unit) {
        val bm = BiometricManager.from(this)
        val canBio = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

        if (canBio) {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        action()
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (settings.isPasswordEnabled) askPasswordThen(action)
                    }
                    override fun onAuthenticationFailed() {}
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("تحقّق من هويتك")
                .setSubtitle("لعرض المحتوى المحمي")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            try { prompt.authenticate(info) } catch (_: Exception) { action() }
        } else if (settings.isPasswordEnabled) {
            askPasswordThen(action)
        } else {
            action()
        }
    }

    private fun askPasswordThen(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_single_password, null)
        val tvLabel = view.findViewById<TextView>(R.id.tv_label)
        val et = view.findViewById<EditText>(R.id.et_password)
        tvLabel.text = "كلمة المرور الاحتياطية"
        et.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                et.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        } catch (_: Throwable) {}

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("أدخل كلمة المرور")
            .setView(view)
            .setPositiveButton("تحقق", null)
            .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
            .create()

        ScreenshotBlocker.enable(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (settings.verifyPassword(et.text.toString())) {
                    dialog.dismiss(); action()
                } else {
                    android.widget.Toast.makeText(this, "كلمة مرور غير صحيحة", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        try { dialog.show() } catch (_: Exception) {}
    }


    private fun refresh() {
        val q = currentQuery

        // 2) هل المُدخل هو "الكلمة السرية"؟
        val ghosts = if (q.isNotEmpty())
            secure.revealGhostNotesIfCodeMatches(q, settings.ghostCodeHash)
        else emptyList()

        val (notes, ghostMode) = when {
            ghosts.isNotEmpty() -> ghosts to true
            q.isNotEmpty()      -> secure.searchRegularNotes(q) to false
            else                -> secure.getRegularNotes() to false
        }

        adapter.setItems(notes)

        if (notes.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvNotes.visibility = View.GONE
            binding.tvStatus.text = if (q.isNotEmpty()) "لا نتائج لبحثك"
                else "لا لحظات بعد — ابدأ واحدة"
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvNotes.visibility = View.VISIBLE
            binding.tvStatus.text = when {
                ghostMode -> "👻 ${notes.size} ملاحظة شبحية"
                q.isNotEmpty() -> "${notes.size} نتيجة"
                else -> "${notes.size} لحظة حيّة"
            }
        }

        val wipes = secure.getWipeCount()
        if (wipes > 0) {
            binding.tvWipeCount.visibility = View.VISIBLE
            binding.tvWipeCount.text = "💀 $wipes انتحار رقمي"
        } else binding.tvWipeCount.visibility = View.GONE
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (isFinishing || isDestroyed) break
                val removed = secure.purgeExpiredNotes()
                if (removed > 0) {
                    refresh()
                } else {
                    try { adapter.notifyItemRangeChanged(0, adapter.itemCount, "tick") } catch (_: Throwable) {}
                }
            }
        }
    }



    private fun openNote(note: Note) {
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_note_view, null)
        val iv = view.findViewById<android.widget.ImageView>(R.id.iv_content)
        val tv = view.findViewById<android.widget.TextView>(R.id.tv_content)
        val tvMeta = view.findViewById<android.widget.TextView>(R.id.tv_meta)
        val layoutAtts = view.findViewById<android.widget.LinearLayout>(R.id.layout_attachments_view)

        if (note.type == Note.NoteType.IMAGE) {
            val bytes = secure.decryptNoteImage(note)
            val bmp = if (bytes != null) safeDecodeBitmap(bytes) else null
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
                if (note.imageNote.isNotBlank()) {
                    tv.text = note.imageNote
                    tv.visibility = View.VISIBLE
                } else {
                    tv.visibility = View.GONE
                }
            } else {
                iv.visibility = View.GONE
                tv.text = "— تعذّر فك تشفير الصورة —"
                tv.visibility = View.VISIBLE
            }
        } else {
            tv.text = secure.decryptNoteText(note) ?: "—"
            tv.visibility = View.VISIBLE
            iv.visibility = View.GONE
        }

        populateAttachments(layoutAtts, note)

        tvMeta.text = buildString {
            append("الصلاحية: ${note.expiryPolicy.displayLabel}")
            if (note.destroyOnRead) append("  •  💥 تدمّر بعد الإغلاق")
            if (note.attachments.isNotEmpty()) append("  •  📎 ${note.attachments.size} مرفق")
            if (note.isGhost) append("  •  👻 شبحية")
        }

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setView(view)
            .setPositiveButton("إغلاق") { d, _ -> d.dismiss() }
            .create()
        ScreenshotBlocker.enable(dialog)
        dialog.setOnDismissListener {
            secure.wipeDecryptedCache()
            if (note.destroyOnRead) {
                secure.markAsRead(note.id)
                secure.purgeExpiredNotes()
                refresh()
            }
        }
        try { dialog.show() } catch (_: Exception) {}
    }


    private fun safeDecodeBitmap(bytes: ByteArray): android.graphics.Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inSampleSize = 1
                inJustDecodeBounds = false
                inMutable = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    inSampleSize = 1
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: Throwable) { null }
        } catch (_: Exception) { null }
    }


    private fun populateAttachments(container: android.widget.LinearLayout, note: com.bum.app.models.Note) {
        container.removeAllViews()
        if (note.attachments.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val header = android.widget.TextView(this).apply {
            text = "📎 مرفقات مشفّرة — تُفتح داخل التطبيق"
            textSize = 12f
            setTextColor(getColor(R.color.bum_text_dim))
            setPadding(0, 0, 0, 6)
        }
        container.addView(header)
        note.attachments.forEach { att ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_input_field)
                setPadding(20, 18, 20, 18)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 8
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }
            val icon = if (att.isImage()) "🖼" else "📄"
            val tvName = android.widget.TextView(this).apply {
                val lp = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                layoutParams = lp
                text = "$icon  ${att.filename}\n${humanSize(att.sizeBytes)} • ${att.mimeType}"
                textSize = 13f
                setTextColor(getColor(R.color.bum_text_primary))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val tvOpen = android.widget.TextView(this).apply {
                text = "فتح ›"
                textSize = 13f
                setTextColor(getColor(R.color.bum_amber))
            }
            row.addView(tvName)
            row.addView(tvOpen)
            row.setOnClickListener { openAttachmentInApp(att) }
            container.addView(row)
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }


    private fun openAttachmentInApp(att: com.bum.app.models.Attachment) {
        try {
            val i = Intent(this, AttachmentViewerActivity::class.java)
            i.putExtra(AttachmentViewerActivity.EXTRA_ATT_JSON, att.toJson().toString())
            startActivity(i)
            overridePendingTransition(R.anim.slide_up_fade, android.R.anim.fade_out)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "تعذّر فتح المرفق", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(note: Note) {
        if (isFinishing || isDestroyed) return
        val d = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("حذف هذه اللحظة؟")
            .setMessage("لا يمكن التراجع. سيُمسح المحتوى بشكل آمن.")
            .setPositiveButton("حذف") { dd, _ ->
                secure.deleteNote(note.id)
                refresh()
                dd.dismiss()
            }
            .setNegativeButton("إلغاء") { dd, _ -> dd.dismiss() }
            .create()
        ScreenshotBlocker.enable(d)
        try { d.show() } catch (_: Exception) {}
    }




    private fun animateEntrance() {
        val views = listOf(
            binding.tvDate, binding.tvGreeting, binding.tvStatus,
            binding.layoutSearch, binding.rvNotes, binding.btnWrite
        )
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 8f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 30L).setDuration(220).start()
        }
    }

    private fun showPhilosophyDialog() {
        if (isFinishing || isDestroyed) return
        val d = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("ما هو Būm؟")
            .setMessage(
                "Būm مكان آمن للأفكار العابرة.\n\n" +
                "• القائمة الرئيسية: لحظات بتواريخ انتهاء اختيارية،\n" +
                "  مع نص أو صور أو مرفقات مشفّرة.\n\n" +
                "• 👻 الملاحظة الشبحية: لا تظهر في القائمة أبداً.\n" +
                "  تكشفها فقط بكتابة \"الكلمة السرية\" في شريط البحث\n" +
                "  (افتراضياً: 0000 — قابلة للتغيير من الإعدادات).\n\n" +
                "• القسم المشفّر المنفصل: واجهة مستقلة، يتطلب دخولها\n" +
                "  بصمة/كلمة مرور، وعناوين واضحة لكل ملاحظة.\n\n" +
                "• مخزن المفاتيح: كلمات مرور وAPI Keys دائمة.\n\n" +
                "المرفقات تُشفَّر لحظياً بـ AES-256-GCM وتُحفظ داخل التطبيق.\n" +
                "تُعرض بعارض مدمج لا يُخرج الملف إلى أي تطبيق آخر،\n" +
                "ويمكنك \"تصديرها عكسياً\" إلى Downloads عند الحاجة."
            )
            .setPositiveButton("فهمت") { dd, _ -> dd.dismiss() }
            .create()
        ScreenshotBlocker.enable(d)
        try { d.show() } catch (_: Exception) {}
    }
}
