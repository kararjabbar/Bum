package com.bum.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.databinding.ActivityVaultBinding
import com.bum.app.models.VaultKey
import com.bum.app.security.ScreenshotBlocker
import com.bum.app.security.SecureDataManager
import com.bum.app.utils.AppSettings
import com.bum.app.utils.DateUtils
import com.bum.app.utils.PasswordGenerator

// مخزن المفاتيح المشفّر
class VaultActivity : BaseSecureActivity() {

    private lateinit var binding: ActivityVaultBinding
    private lateinit var secure: SecureDataManager
    private lateinit var settings: AppSettings
    private lateinit var adapter: VaultAdapter

    // كل المفاتيح — نحتفظ بها للبحث الفوري
    private var allKeys: List<VaultKey> = emptyList()
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarPadding(binding.header, extraPaddingDp = 10)

        disableAutofillOnRoot(binding.root)

        secure = SecureDataManager.getInstance(this)
        settings = AppSettings.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.fabAdd.setOnClickListener { showEditDialog(null) }

        adapter = VaultAdapter(
            items = mutableListOf(),
            onView   = { requireAuthThen { showSecretDialog(it) } },
            onEdit   = { requireAuthThen { showEditDialog(it) } },
            onDelete = { confirmDelete(it) }
        )
        binding.rvVault.layoutManager = LinearLayoutManager(this)
        binding.rvVault.adapter = adapter


        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (BumApplication.lifecycleObserver.sessionLocked) {
            startActivity(android.content.Intent(this, BiometricActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }
        refresh()
    }

    private fun refresh() {
        allKeys = secure.getAllVaultKeys()
        applyFilter()
    }

    private fun applyFilter() {
        val q = currentQuery.lowercase()
        val filtered = if (q.isEmpty()) allKeys
        else allKeys.filter {
            it.label.lowercase().contains(q) ||
            it.username.lowercase().contains(q) ||
            it.categoryName.lowercase().contains(q) ||
            it.note.lowercase().contains(q)
        }
        adapter.setItems(filtered)

        val empty = filtered.isEmpty()
        binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.tvEmptyMessage.text =
            if (allKeys.isEmpty()) "لا مفاتيح محفوظة بعد"
            else "لا نتائج تطابق البحث"
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
                        else toast("فشلت المصادقة")
                    }
                    override fun onAuthenticationFailed() {}
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("تحقق من هويتك")
                .setSubtitle("للوصول إلى القيمة السرية")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
        } else if (settings.isPasswordEnabled) {
            askPasswordThen(action)
        } else {
            action()
        }
    }

    private fun askPasswordThen(action: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_single_password, null)
        val tvLabel = view.findViewById<TextView>(R.id.tv_label)
        val et = view.findViewById<EditText>(R.id.et_password)
        tvLabel.text = "كلمة المرور الاحتياطية"
        et.transformationMethod = PasswordTransformationMethod.getInstance()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                    toast("كلمة مرور غير صحيحة")
                }
            }
        }
        dialog.show()
    }



    private fun showSecretDialog(key: VaultKey) {
        if (isFinishing || isDestroyed) return
        val secret = secure.revealVaultSecret(key.id) ?: run {
            toast("تعذّر استرجاع السر"); return
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        if (key.username.isNotBlank()) {
            val tvUser = TextView(this).apply {
                text = key.username
                textSize = 14f
                setTextColor(resolveAttr(android.R.attr.textColorPrimary))
                typeface = android.graphics.Typeface.MONOSPACE
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setTextIsSelectable(true)
                setPadding(20, 18, 20, 18)
                setBackgroundResource(R.drawable.bg_input_field)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 12
                layoutParams = lp
            }
            container.addView(tvUser)
        }

        val tvSecret = TextView(this).apply {
            text = secret
            textSize = 16f
            setTextColor(resolveAttr(android.R.attr.textColorPrimary))
            typeface = android.graphics.Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
            setPadding(20, 20, 20, 20)
            setBackgroundResource(R.drawable.bg_input_field)
        }
        container.addView(tvSecret)

        val actionsRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 18, 0, 0)
        }

        val btnCopySecret = android.widget.Button(this).apply {
            text = "📋  نسخ السر"
            textSize = 13f
            setTextColor(resolveAttr(android.R.attr.textColorPrimary))
            setBackgroundResource(R.drawable.bg_btn_view)
            val lp = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            lp.marginEnd = 8
            layoutParams = lp
            setOnClickListener {
                copySensitiveToClipboard("سر ${key.label}", secret)
                toast("✓ نُسخ إلى الحافظة")
            }
        }
        actionsRow.addView(btnCopySecret)

        if (key.username.isNotBlank()) {
            val btnCopyUser = android.widget.Button(this).apply {
                text = "👤  نسخ المستخدم"
                textSize = 13f
                setTextColor(resolveAttr(android.R.attr.textColorPrimary))
                setBackgroundResource(R.drawable.bg_btn_view)
                val lp = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                layoutParams = lp
                setOnClickListener {
                    copySensitiveToClipboard("مستخدم ${key.label}", key.username)
                    toast("✓ نُسخ إلى الحافظة")
                }
            }
            actionsRow.addView(btnCopyUser)
        }
        container.addView(actionsRow)

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle(key.label)
            .setView(container)
            .setPositiveButton("إغلاق") { d, _ -> d.dismiss() }
            .create()

        ScreenshotBlocker.enable(dialog)
        try { dialog.show() } catch (_: Exception) {}
    }

    // نسخ آمن إلى الحافظة
    private fun copySensitiveToClipboard(label: String, value: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                clip.description.extras = extras
            }
        } catch (_: Throwable) { }
        cm.setPrimaryClip(clip)
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }



    private fun showEditDialog(existing: VaultKey?) {
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_vault_edit, null)
        val etLabel    = view.findViewById<EditText>(R.id.et_label)
        val etUsername = view.findViewById<EditText>(R.id.et_username)
        val etSecret   = view.findViewById<EditText>(R.id.et_secret)
        val etNote     = view.findViewById<EditText>(R.id.et_note)
        val spCat      = view.findViewById<android.widget.Spinner>(R.id.sp_category)
        val btnToggle  = view.findViewById<android.widget.ImageButton>(R.id.btn_toggle_secret)
        val btnAddCat  = view.findViewById<android.widget.ImageButton>(R.id.btn_add_category)
        val btnGen     = view.findViewById<android.widget.ImageButton>(R.id.btn_generate_password)
        val tvStrength = view.findViewById<TextView>(R.id.tv_strength)
        val tvHistory  = view.findViewById<TextView>(R.id.tv_history)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                etLabel.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                etUsername.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                etSecret.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                etNote.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            }
        } catch (_: Throwable) { }

        var secretVisible = false

        fun refreshSecretToggleIcon() {
            btnToggle.setImageResource(
                if (secretVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
        }

        etSecret.transformationMethod = PasswordTransformationMethod.getInstance()
        refreshSecretToggleIcon()
        btnToggle.setOnClickListener {
            secretVisible = !secretVisible
            etSecret.transformationMethod =
                if (secretVisible) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            refreshSecretToggleIcon()
            etSecret.setSelection(etSecret.text?.length ?: 0)
        }


        etSecret.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val p = s?.toString() ?: ""
                if (p.isEmpty()) { tvStrength.text = ""; return }
                val bits = PasswordGenerator.entropyBits(p)
                tvStrength.text = "قوة: ${PasswordGenerator.strengthLabel(bits)} (${String.format("%.0f", bits)} bits)"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        fun buildCategoryList(): List<String> {
            val custom = settings.getCustomVaultCategories()
            return if (custom.isEmpty()) listOf("عام") else custom
        }

        var categories = buildCategoryList()
        var catAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        spCat.adapter = catAdapter

        btnAddCat.setOnClickListener {
            val etNew = EditText(this).apply {
                hint = "اسم الفئة الجديدة"
                setBackgroundResource(R.drawable.bg_input_field)
                setPadding(40, 28, 40, 28)
            }
            val container = android.widget.FrameLayout(this).apply {
                setPadding(48, 8, 48, 8)
                addView(etNew)
            }
            val d = AlertDialog.Builder(this, R.style.BumAlertDialog)
                .setTitle("فئة جديدة")
                .setView(container)
                .setPositiveButton("إضافة") { dd, _ ->
                    val name = etNew.text.toString().trim()
                    if (name.isNotEmpty()) {
                        settings.addCustomVaultCategory(name)
                        categories = buildCategoryList()
                        catAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
                        spCat.adapter = catAdapter
                        spCat.setSelection(categories.indexOf(name).coerceAtLeast(0))
                    }
                    dd.dismiss()
                }
                .setNegativeButton("إلغاء") { dd, _ -> dd.dismiss() }
                .create()
            ScreenshotBlocker.enable(d)
            d.show()
        }

        btnGen.setOnClickListener { showPasswordGenerator { pwd ->
            etSecret.setText(pwd)
            // أظهرها مؤقتاً للمستخدم ليرى ما وُلد
            etSecret.transformationMethod = HideReturnsTransformationMethod.getInstance()
            secretVisible = true
            refreshSecretToggleIcon()
        } }

        existing?.let {
            etLabel.setText(it.label)
            etUsername.setText(it.username)
            etSecret.setText(secure.peekVaultSecret(it.id) ?: "")
            etNote.setText(it.note)
            val idx = categories.indexOf(it.categoryName).coerceAtLeast(0)
            spCat.setSelection(idx)

            val created = DateUtils.friendlyFromMillis(it.createdAt)
            val updated = DateUtils.friendlyFromMillis(it.updatedAt)
            val accessed = if (it.lastAccessedAt > 0) DateUtils.friendlyFromMillis(it.lastAccessedAt) else "—"
            tvHistory.text = "📜  أُنشئ: $created\n✏  آخر تعديل: $updated\n👁  آخر عرض: $accessed"
            tvHistory.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle(if (existing == null) "إضافة مفتاح جديد" else "تعديل المفتاح")
            .setView(view)
            .setPositiveButton("حفظ") { d, _ ->
                val label = etLabel.text.toString().trim()
                val username = etUsername.text.toString().trim()
                val secret = etSecret.text.toString()
                if (label.isEmpty() || secret.isEmpty()) {
                    toast("الاسم والقيمة السرية إلزاميان"); return@setPositiveButton
                }
                val selectedCat = categories.getOrElse(spCat.selectedItemPosition) { "عام" }
                secure.upsertVaultKey(
                    id = existing?.id,
                    label = label,
                    username = username,
                    categoryName = selectedCat,
                    plainSecret = secret,
                    note = etNote.text.toString()
                )
                d.dismiss()
                refresh()
            }
            .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
            .create()

        ScreenshotBlocker.enable(dialog)
        dialog.show()
    }



    private fun showPasswordGenerator(onPick: (String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_password_generator, null)
        val tvGenerated = view.findViewById<TextView>(R.id.tv_generated)
        val tvMeter     = view.findViewById<TextView>(R.id.tv_strength_meter)
        val tvLenVal    = view.findViewById<TextView>(R.id.tv_length_value)
        val sbLen       = view.findViewById<SeekBar>(R.id.sb_length)
        val swUpper     = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_upper)
        val swLower     = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_lower)
        val swDigits    = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_digits)
        val swSymbols   = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_symbols)
        val swAmbig     = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_avoid_ambig)
        val btnRegen    = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_regenerate)

        fun currentOptions() = PasswordGenerator.Options(
            length = 8 + sbLen.progress,
            useUpper = swUpper.isChecked,
            useLower = swLower.isChecked,
            useDigits = swDigits.isChecked,
            useSymbols = swSymbols.isChecked,
            avoidAmbiguous = swAmbig.isChecked
        )

        fun regenerate() {
            val p = PasswordGenerator.generate(currentOptions())
            tvGenerated.text = p
            val bits = PasswordGenerator.entropyBits(p)
            tvMeter.text = "قوة: ${PasswordGenerator.strengthLabel(bits)}  •  ${String.format("%.0f", bits)} bits"
        }

        sbLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvLenVal.text = "${8 + p}"
                if (fromUser) regenerate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val switchListener = CompoundButton.OnCheckedChangeListener { _, _ -> regenerate() }
        swUpper.setOnCheckedChangeListener(switchListener)
        swLower.setOnCheckedChangeListener(switchListener)
        swDigits.setOnCheckedChangeListener(switchListener)
        swSymbols.setOnCheckedChangeListener(switchListener)
        swAmbig.setOnCheckedChangeListener(switchListener)

        btnRegen.setOnClickListener { regenerate() }

        sbLen.progress = 12
        tvLenVal.text = "20"
        regenerate()

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("🎲  مولّد كلمات مرور قوية")
            .setView(view)
            .setPositiveButton("استخدام") { d, _ ->
                onPick(tvGenerated.text.toString())
                d.dismiss()
            }
            .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
            .create()

        ScreenshotBlocker.enable(dialog)
        dialog.show()
    }

    private fun confirmDelete(key: VaultKey) {
        val d = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("حذف المفتاح؟")
            .setMessage("لا يمكن استعادة «${key.label}» بعد الحذف.")
            .setPositiveButton("حذف") { dd, _ ->
                secure.deleteVaultKey(key.id)
                refresh()
                dd.dismiss()
            }
            .setNegativeButton("إلغاء") { dd, _ -> dd.dismiss() }
            .create()
        ScreenshotBlocker.enable(d)
        d.show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()


    private fun disableAutofillOnRoot(root: View) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                root.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            }
        } catch (_: Throwable) { }
    }
}



class VaultAdapter(
    private var items: MutableList<VaultKey>,
    private val onView: (VaultKey) -> Unit,
    private val onEdit: (VaultKey) -> Unit,
    private val onDelete: (VaultKey) -> Unit
) : RecyclerView.Adapter<VaultAdapter.VH>() {

    fun setItems(list: List<VaultKey>) {
        items = list.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_key, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val k = items[pos]
        h.tvLabel.text = k.label
        if (k.username.isNotBlank()) {
            h.tvUsername.text = k.username
            h.tvUsername.visibility = View.VISIBLE
        } else {
            h.tvUsername.visibility = View.GONE
        }
        h.tvCategory.text = k.categoryName
        h.tvNote.text = if (k.note.isNotBlank()) k.note else "—"
        h.tvSecret.text = "•••••••••••••"
        h.tvCreatedAt.text  = "أُنشئ: ${DateUtils.friendlyFromMillis(k.createdAt)}"
        h.tvUpdatedAt.text  = "آخر تعديل: ${DateUtils.friendlyFromMillis(k.updatedAt)}"
        h.tvLastAccessed.text = if (k.lastAccessedAt > 0)
            "آخر عرض: ${DateUtils.friendlyFromMillis(k.lastAccessedAt)}"
        else "لم يُعرض بعد"

        h.itemView.setOnClickListener { onView(k) }
        h.btnEdit.setOnClickListener   { onEdit(k) }
        h.btnDelete.setOnClickListener { onDelete(k) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvLabel: TextView = v.findViewById(R.id.tv_label)
        val tvUsername: TextView = v.findViewById(R.id.tv_username)
        val tvCategory: TextView = v.findViewById(R.id.tv_category)
        val tvNote: TextView = v.findViewById(R.id.tv_note)
        val tvSecret: TextView = v.findViewById(R.id.tv_secret)
        val tvCreatedAt: TextView = v.findViewById(R.id.tv_created_at)
        val tvUpdatedAt: TextView = v.findViewById(R.id.tv_updated_at)
        val tvLastAccessed: TextView = v.findViewById(R.id.tv_last_accessed)
        val btnEdit: View = v.findViewById(R.id.btn_edit)
        val btnDelete: View = v.findViewById(R.id.btn_delete)
    }
}
