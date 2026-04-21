package com.bum.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.databinding.ActivityEncryptedNotesBinding
import com.bum.app.models.Note
import com.bum.app.security.ScreenshotBlocker
import com.bum.app.security.SecureDataManager
import com.bum.app.utils.AppSettings
import com.bum.app.utils.ThemeGlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// قسم الملاحظات المشفّرة المنفصل
class EncryptedNotesActivity : BaseSecureActivity() {

    private lateinit var binding: ActivityEncryptedNotesBinding
    private lateinit var secure: SecureDataManager
    private lateinit var settings: AppSettings
    private lateinit var adapter: EncryptedNoteAdapter
    private var tickerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEncryptedNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarPadding(binding.header, extraPaddingDp = 10)

        secure = SecureDataManager.getInstance(this)
        settings = AppSettings.getInstance(this)

        ThemeGlow.applyAccentGlow(binding.viewGlowBg, this)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnNewSecret.setOnClickListener {
            // الانتقال إلى WriteActivity في وضع "القسم المشفّر"
            val intent = Intent(this, WriteActivity::class.java).apply {
                putExtra(WriteActivity.EXTRA_SECRET_COMPARTMENT, true)
            }
            startActivity(intent)
            overridePendingTransition(R.anim.slide_up_fade, android.R.anim.fade_out)
        }

        adapter = EncryptedNoteAdapter(
            items = mutableListOf(),
            secure = secure,
            onOpen = { note -> requireAuthThen { openNote(note) } },
            onDelete = { confirmDelete(it) }
        )
        binding.rvEncrypted.layoutManager = LinearLayoutManager(this)
        binding.rvEncrypted.adapter = adapter
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

    private fun refresh() {
        val notes = secure.getSecretCompartmentNotes()
        adapter.setItems(notes)
        binding.layoutEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        binding.rvEncrypted.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        binding.tvCountBadge.text = notes.size.toString()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (isFinishing || isDestroyed) break
                val removed = secure.purgeExpiredNotes()
                if (removed > 0) refresh()
                else try { adapter.notifyItemRangeChanged(0, adapter.itemCount, "tick") } catch (_: Throwable) {}
            }
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
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { action() }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (settings.isPasswordEnabled) askPasswordThen(action)
                    }
                    override fun onAuthenticationFailed() {}
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("تحقّق من هويتك")
                .setSubtitle("لفتح الملاحظة المشفّرة")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            try { prompt.authenticate(info) } catch (_: Exception) { action() }
        } else if (settings.isPasswordEnabled) askPasswordThen(action)
        else action()
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



    private fun openNote(note: Note) {
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_note_view, null)
        val iv = view.findViewById<android.widget.ImageView>(R.id.iv_content)
        val tv = view.findViewById<android.widget.TextView>(R.id.tv_content)
        val tvMeta = view.findViewById<android.widget.TextView>(R.id.tv_meta)
        val layoutAtts = view.findViewById<android.widget.LinearLayout>(R.id.layout_attachments_view)

        if (note.type == Note.NoteType.IMAGE) {
            val bytes = secure.decryptNoteImage(note)
    
            val bmp = if (bytes != null) try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: Throwable) { null } else null
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
                if (note.imageNote.isNotBlank()) {
                    tv.text = note.imageNote
                    tv.visibility = View.VISIBLE
                } else tv.visibility = View.GONE
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

        // المرفقات
        populateAttachments(layoutAtts, note)

        val decryptedTitle = secure.decryptNoteTitle(note)
        tvMeta.text = buildString {
            if (decryptedTitle.isNotBlank()) append("📌 $decryptedTitle\n")
            append("الصلاحية: ${note.expiryPolicy.displayLabel}")
            if (note.destroyOnRead) append("  •  💥 تدمّر بعد الإغلاق")
            if (note.attachments.isNotEmpty()) append("  •  📎 ${note.attachments.size} مرفق")
        }

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle(if (decryptedTitle.isNotBlank()) "🔐  $decryptedTitle" else "🔐  ملاحظة مشفّرة")
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

    private fun populateAttachments(container: android.widget.LinearLayout, note: Note) {
        container.removeAllViews()
        if (note.attachments.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val header = android.widget.TextView(this).apply {
            text = "📎 مرفقات مشفّرة"
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
            row.setOnClickListener { openAttachment(att) }
            container.addView(row)
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }


    private fun openAttachment(att: com.bum.app.models.Attachment) {
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
            .setTitle("حذف هذه الملاحظة المشفّرة؟")
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
}


class EncryptedNoteAdapter(
    private var items: MutableList<Note>,
    private val secure: SecureDataManager,
    private val onOpen: (Note) -> Unit,
    private val onDelete: (Note) -> Unit
) : RecyclerView.Adapter<EncryptedNoteAdapter.VH>() {

    fun setItems(list: List<Note>) {
        items = list.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_encrypted_note, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.contains("tick")) {
            val n = items[position]
            h.tvPolicy.text = policyLabel(n)
            return
        }
        super.onBindViewHolder(h, position, payloads)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val n = items[position]


        val decryptedTitle = secure.decryptNoteTitle(n)
        h.tvTitle.text = if (decryptedTitle.isNotBlank()) decryptedTitle else "— بدون عنوان —"

        val df = SimpleDateFormat("HH:mm • dd/MM", Locale.getDefault())
        h.tvDate.text = df.format(Date(n.createdAt))
        h.tvPolicy.text = policyLabel(n)

        if (n.attachments.isNotEmpty()) {
            h.tvAttachments.text = "📎 ${n.attachments.size}"
            h.tvAttachments.visibility = View.VISIBLE
        } else h.tvAttachments.visibility = View.GONE

        h.tvDestroy.visibility = if (n.destroyOnRead) View.VISIBLE else View.GONE

        h.itemView.setOnClickListener { onOpen(n) }
        h.btnDelete.setOnClickListener { onDelete(n) }
    }

    private fun policyLabel(n: Note): String = when (n.expiryPolicy) {
        Note.ExpiryPolicy.PERMANENT -> "♾ دائم"
        Note.ExpiryPolicy.ON_APP_CLOSE -> "⚡ يموت عند الإغلاق"
        else -> {
            val remain = n.remainingMs()
            if (remain <= 0L) "⌛ منتهية"
            else "⏳ ${formatRemain(remain)}"
        }
    }

    private fun formatRemain(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}ث"
            sec < 3600 -> "${sec/60}د"
            sec < 86400 -> "${sec/3600}س"
            else -> "${sec/86400}ي"
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tv_title)
        val tvDate: TextView = v.findViewById(R.id.tv_date)
        val tvPolicy: TextView = v.findViewById(R.id.tv_policy)
        val tvAttachments: TextView = v.findViewById(R.id.tv_attachments)
        val tvDestroy: TextView = v.findViewById(R.id.tv_destroy)
        val btnDelete: ImageButton = v.findViewById(R.id.btn_delete)
    }
}
