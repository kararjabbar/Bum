package com.bum.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bum.app.BuildConfig
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.databinding.ActivitySettingsBinding
import com.bum.app.models.Note
import com.bum.app.security.ScreenshotBlocker
import com.bum.app.security.SecureDataManager
import com.bum.app.utils.AppSettings
import com.bum.app.utils.BumTheme
import com.bum.app.utils.ThemeManager

// شاشة الإعدادات
class SettingsActivity : BaseSecureActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings
    private lateinit var secure: SecureDataManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarPadding(binding.header, extraPaddingDp = 10)

        settings = AppSettings.getInstance(this)
        secure = SecureDataManager.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvVersion.text = "Būm v${BuildConfig.VERSION_NAME}"

        setupTheme()
        setupGrace()
        setupDefaults()
        setupWipeOnClose()
        setupPassword()
        setupGhostCode()
        setupDangerZone()
    }

    override fun onResume() {
        super.onResume()
        if (BumApplication.lifecycleObserver.sessionLocked) {
            startActivity(android.content.Intent(this, BiometricActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    private fun setupTheme() {
        val themes = BumTheme.values()
        val labels = themes.map { it.displayName }
        binding.spTheme.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        val currentKey = settings.selectedThemeKey
        val idx = themes.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        binding.spTheme.setSelection(idx)
        var firstCall = true
        binding.spTheme.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: android.view.View?,
                    position: Int, id: Long
                ) {
                    if (firstCall) { firstCall = false; return }
                    val chosen = themes[position]
                    if (chosen.key != settings.selectedThemeKey) {
                        settings.selectedThemeKey = chosen.key
                        toast("تم تفعيل ثيم «${chosen.displayName}»")
                        ThemeManager.recreateActivity(this@SettingsActivity)
                    }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
    }



    private fun setupGrace() {
        binding.swGraceEnabled.isChecked = settings.isGraceEnabled
        updateGraceUi()

        binding.swGraceEnabled.setOnCheckedChangeListener { _, v ->
            settings.isGraceEnabled = v
            updateGraceUi()
        }

        binding.seekGrace.max = AppSettings.MAX_GRACE_SECONDS - AppSettings.MIN_GRACE_SECONDS
        binding.seekGrace.progress = settings.gracePeriodSeconds - AppSettings.MIN_GRACE_SECONDS

        binding.seekGrace.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val sec = AppSettings.MIN_GRACE_SECONDS + p
                binding.tvGraceValue.text = humanDuration(sec)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val sec = AppSettings.MIN_GRACE_SECONDS + (s?.progress ?: 0)
                settings.gracePeriodSeconds = sec
                toast("تم ضبط فترة السماح على ${humanDuration(sec)}")
            }
        })

        binding.tvGraceValue.text = humanDuration(settings.gracePeriodSeconds)
    }

    private fun updateGraceUi() {
        val enabled = settings.isGraceEnabled
        binding.seekGrace.isEnabled = enabled
        binding.tvGraceValue.alpha = if (enabled) 1f else 0.4f
    }

    private fun humanDuration(sec: Int) = when {
        sec < 60 -> "$sec ثانية"
        sec % 60 == 0 -> "${sec/60} دقيقة"
        else -> "${sec/60} د ${sec%60} ث"
    }



    private fun setupDefaults() {
        val policies = Note.ExpiryPolicy.values()
        val labels = policies.map { it.displayLabel }
        binding.spDefaultExpiry.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        val idx = policies.indexOfFirst { it.name == settings.defaultExpiryPolicyName }
            .coerceAtLeast(0)
        binding.spDefaultExpiry.setSelection(idx)
        binding.spDefaultExpiry.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: android.view.View?,
                    position: Int, id: Long
                ) { settings.defaultExpiryPolicyName = policies[position].name }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        binding.swDefaultDestroy.isChecked = settings.defaultDestroyOnRead
        binding.swDefaultDestroy.setOnCheckedChangeListener { _, v ->
            settings.defaultDestroyOnRead = v
        }
    }



    private fun setupWipeOnClose() {
        binding.swWipeOnClose.isChecked = settings.wipeAllOnClose
        binding.swWipeOnClose.setOnCheckedChangeListener { _, v ->
            settings.wipeAllOnClose = v
            if (v) toast("تم التفعيل: اللحظات الموسومة بـ «يموت عند الإغلاق» ستُمحى تلقائياً.")
        }
    }



    private fun setupPassword() {
        refreshPasswordUi()

        binding.btnSetPassword.setOnClickListener {
            if (settings.isPasswordEnabled) {
                showChangeOrRemovePasswordDialog()
            } else {
                showSetPasswordDialog()
            }
        }
    }

    private fun refreshPasswordUi() {
        if (settings.isPasswordEnabled) {
            binding.tvPasswordStatus.text = "✅  كلمة مرور احتياطية مفعّلة"
            binding.btnSetPassword.text = "تغيير / إزالة كلمة المرور"
        } else {
            binding.tvPasswordStatus.text = "كلمة مرور غير مفعّلة"
            binding.btnSetPassword.text = "تعيين كلمة مرور"
        }
    }

    private fun showSetPasswordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_set_password, null)
        val etPass    = view.findViewById<EditText>(R.id.et_password)
        val etConfirm = view.findViewById<EditText>(R.id.et_password_confirm)
        val tvMatch   = view.findViewById<TextView>(R.id.tv_match_indicator)

        etPass.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        etConfirm.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                etPass.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                etConfirm.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        } catch (_: Throwable) {}

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val p = etPass.text?.toString().orEmpty()
                val c = etConfirm.text?.toString().orEmpty()
                when {
                    p.isEmpty() && c.isEmpty() -> { tvMatch.text = " " }
                    p.length < 4 -> {
                        tvMatch.text = "⚠  كلمة المرور قصيرة (4 أحرف على الأقل)"
                    }
                    c.isEmpty() -> {
                        tvMatch.text = "أدخل تأكيد كلمة المرور"
                    }
                    p == c -> { tvMatch.text = "✓  متطابقتان" }
                    else -> { tvMatch.text = "✕  غير متطابقتين" }
                }
            }
        }
        etPass.addTextChangedListener(watcher)
        etConfirm.addTextChangedListener(watcher)

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("تعيين كلمة مرور احتياطية")
            .setView(view)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
            .create()

        ScreenshotBlocker.enable(dialog)

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val p = etPass.text.toString()
                val c = etConfirm.text.toString()
                when {
                    p.length < 4 -> toast("كلمة المرور قصيرة جداً (4 أحرف على الأقل)")
                    p != c       -> toast("كلمتا المرور غير متطابقتين")
                    else -> {
                        settings.setPassword(p)
                        refreshPasswordUi()
                        toast("تم تعيين كلمة المرور")
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showChangeOrRemovePasswordDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_single_password, null)
        val tvLabel  = view.findViewById<TextView>(R.id.tv_label)
        val etCurrent = view.findViewById<EditText>(R.id.et_password)
        tvLabel.text = "كلمة المرور الحالية"
        etCurrent.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                etCurrent.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        } catch (_: Throwable) {}

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("إدارة كلمة المرور")
            .setView(view)
            .setPositiveButton("تغيير", null)
            .setNeutralButton("إزالة", null)
            .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
            .create()

        ScreenshotBlocker.enable(dialog)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!settings.verifyPassword(etCurrent.text.toString())) {
                    toast("كلمة المرور الحالية غير صحيحة"); return@setOnClickListener
                }
                dialog.dismiss()
                showSetPasswordDialog()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!settings.verifyPassword(etCurrent.text.toString())) {
                    toast("كلمة المرور الحالية غير صحيحة"); return@setOnClickListener
                }
                settings.removePassword()
                refreshPasswordUi()
                toast("تم إزالة كلمة المرور")
                dialog.dismiss()
            }
        }
        dialog.show()
    }



    private fun setupGhostCode() {
        binding.btnChangeGhostCode.setOnClickListener { showChangeGhostCodeDialog() }
    }

    private fun showChangeGhostCodeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_single_password, null)
        val tvLabel = view.findViewById<TextView>(R.id.tv_label)
        val et = view.findViewById<EditText>(R.id.et_password)
        tvLabel.text = "الكلمة السرية الجديدة (رقم أو نص — تُكتب في شريط البحث)"
        et.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                et.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        } catch (_: Throwable) {}

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("تغيير كلمة الملاحظة الشبحية")
            .setMessage("اختر كلمة/رقم يصعب تخمينه ولا يستخدم في محتوى الملاحظات.\nالافتراضية: 0000")
            .setView(view)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
            .create()
        ScreenshotBlocker.enable(dialog)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val plain = et.text.toString().trim()
                if (plain.length < 2) {
                    toast("الكلمة قصيرة جداً")
                    return@setOnClickListener
                }
                settings.setGhostCode(plain)
                toast("✓ تمّ حفظ الكلمة السرية الجديدة")
                dialog.dismiss()
            }
        }
        dialog.show()
    }



    private fun setupDangerZone() {
        binding.btnWipeAll.setOnClickListener {
            AlertDialog.Builder(this, R.style.BumAlertDialog)
                .setTitle("مسح كامل وشامل")
                .setMessage(
                    "سيتم حذف كل شيء: جميع اللحظات، جميع مفاتيح الخزنة، والملفات المشفّرة. " +
                    "لا يمكن التراجع."
                )
                .setPositiveButton("نعم، امسح الكل") { d, _ ->
                    secure.wipeEverything()
                    toast("تم المسح الكامل")
                    d.dismiss()
                    finish()
                }
                .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
