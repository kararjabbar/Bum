package com.bum.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.databinding.ActivityWriteBinding
import com.bum.app.models.Attachment
import com.bum.app.models.Note
import com.bum.app.security.ScreenshotBlocker
import com.bum.app.security.SecureDataManager
import com.bum.app.utils.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// شاشة كتابة ملاحظة جديدة
class WriteActivity : BaseSecureActivity() {

    companion object {

        const val EXTRA_SECRET_COMPARTMENT = "extra_secret_compartment"
    }

    private lateinit var binding: ActivityWriteBinding
    private lateinit var secureManager: SecureDataManager
    private lateinit var settings: AppSettings
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentMode = Mode.TEXT
    private var capturedImageFile: File? = null
    private var imageCapture: ImageCapture? = null


    private var isSecretCompartment: Boolean = false


    private var isGhostMode: Boolean = false

    private val pendingAttachments: MutableList<Attachment> = mutableListOf()

    enum class Mode { TEXT, CAMERA, CAPTURED_PREVIEW }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showToast(getString(R.string.camera_permission_denied))
    }


    private val attachmentPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) encryptAndAttachAll(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWriteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarPadding(binding.layoutTopBar, extraPaddingDp = 12)

        secureManager = SecureDataManager.getInstance(this)
        settings = AppSettings.getInstance(this)

        isSecretCompartment = intent.getBooleanExtra(EXTRA_SECRET_COMPARTMENT, false)
        if (isSecretCompartment) {
            binding.layoutTitleWrapper.visibility = View.VISIBLE
            binding.tvHeaderTitle.text = "🔐  ملاحظة مشفّرة جديدة"
            binding.btnGhostToggle.visibility = View.GONE
        } else {
            binding.layoutTitleWrapper.visibility = View.GONE
            binding.tvHeaderTitle.text = "لحظة جديدة"
            binding.btnGhostToggle.visibility = View.VISIBLE
        }

        setupWritingMode()
        setupUI()
        animateEntrance()
    }

    override fun onResume() {
        super.onResume()
        if (BumApplication.lifecycleObserver.sessionLocked) {
            startActivity(android.content.Intent(this, BiometricActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }



    private fun setupWritingMode() {
        binding.layoutWriting.visibility = View.VISIBLE
        binding.layoutCameraMode.visibility = View.GONE
        binding.etContent.requestFocus()
        lifecycleScope.launch {
            delay(250)
            val imm = getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(binding.etContent, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnCamera.setOnClickListener { toggleCameraMode() }

        binding.btnGhostToggle.setOnClickListener {
            isGhostMode = !isGhostMode
            updateGhostToggleUi()
            val msg = if (isGhostMode)
                "👻 وضع الشبح مُفعَّل — لن تظهر في القائمة الرئيسية"
            else "تمّ إيقاف وضع الشبح"
            showToast(msg)
        }
        updateGhostToggleUi()

        binding.btnAttach.setOnClickListener {
            BumApplication.lifecycleObserver.allowOneExternalRoundTrip()
            attachmentPicker.launch(arrayOf("*/*"))
        }

        // زر الحفظ (للنص) — يعرض dialog خيارات الصلاحية
        binding.btnSave.setOnClickListener {
            val text = binding.etContent.text?.toString()?.trim().orEmpty()
            val title = binding.etTitle.text?.toString()?.trim().orEmpty()

            if (isSecretCompartment && title.isEmpty()) {
                binding.etTitle.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
                )
                showToast("العنوان إلزامي في القسم المشفّر")
                return@setOnClickListener
            }
            if (text.isEmpty() && pendingAttachments.isEmpty() && title.isEmpty()) {
                shakeInput(); return@setOnClickListener
            }
            showExpiryDialog { policy, destroyOnRead ->
                val saveText = when {
                    text.isNotEmpty() -> text
                    pendingAttachments.isNotEmpty() -> "📎 مرفقات (${pendingAttachments.size})"
                    else -> "—"
                }
                secureManager.addTextNote(
                    saveText, policy, destroyOnRead,
                    attachments = pendingAttachments.toList(),
                    title = title,
                    isSecretCompartment = isSecretCompartment,
                    isGhost = isGhostMode
                )
                pendingAttachments.clear()
                showSaveAnimationAndFinish()
            }
        }

        binding.btnCapture.setOnClickListener { capturePhoto() }
        binding.btnCancelCamera.setOnClickListener { exitCameraMode() }
        binding.btnRetake.setOnClickListener { retakePhoto() }

        binding.btnSaveImage.setOnClickListener {
            val file = capturedImageFile ?: return@setOnClickListener
            val imageNote = binding.etImageNote.text?.toString()?.trim() ?: ""
            val title = binding.etTitle.text?.toString()?.trim() ?: ""
            if (isSecretCompartment && title.isEmpty()) {
                binding.etTitle.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
                )
                showToast("العنوان إلزامي في القسم المشفّر")
                return@setOnClickListener
            }
            showExpiryDialog { policy, destroyOnRead ->
                secureManager.addImageNote(
                    file, policy, destroyOnRead, imageNote,
                    title = title,
                    isSecretCompartment = isSecretCompartment,
                    isGhost = isGhostMode
                )
                showSaveAnimationAndFinish()
            }
        }

        // عدّاد الأحرف
        binding.etContent.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val count = s?.length ?: 0
                binding.tvCharCount.text = "$count"
                if (count > 450)
                    binding.tvCharCount.setTextColor(getColor(R.color.bum_danger))
                else
                    binding.tvCharCount.setTextColor(getColor(R.color.bum_text_dim))
            }
        })
    }


    private fun requireAuthThen(action: () -> Unit) {
        val settings = AppSettings.getInstance(this)
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
                        else showToast("تم إلغاء إضافة المرفق")
                    }
                    override fun onAuthenticationFailed() {}
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("تحقّق من هويتك")
                .setSubtitle("لإضافة مرفق مشفّر")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
        } else if (settings.isPasswordEnabled) {
            askPasswordThen(action)
        } else {
            // الجهاز لا يدعم البصمة ولا يوجد كلمة مرور — نسمح
            action()
        }
    }

    private fun askPasswordThen(action: () -> Unit) {
        val settings = AppSettings.getInstance(this)
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
                    showToast("كلمة مرور غير صحيحة")
                }
            }
        }
        dialog.show()
    }



    private fun encryptAndAttachAll(uris: List<Uri>) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                val list = mutableListOf<Attachment>()
                for (uri in uris) {
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: continue
                        if (bytes.size > 25 * 1024 * 1024) {
                            continue
                        }
                        val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
                        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                        list += secureManager.encryptAttachmentBytes(bytes, name, mime)
                    } catch (_: Exception) { /* skip this one */ }
                }
                list
            }
            if (added.isEmpty()) {
                showToast("تعذّر قراءة الملفات المختارة")
                return@launch
            }
            pendingAttachments.addAll(added)
            refreshAttachmentsUi()
            showToast("تم تشفير ${added.size} مرفق")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    private fun refreshAttachmentsUi() {
        binding.layoutAttachments.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            binding.scrollAttachments.visibility = View.GONE
            return
        }
        binding.scrollAttachments.visibility = View.VISIBLE
        // نسخة آمنة لتجنب ConcurrentModificationException عند الحذف
        val snapshot = pendingAttachments.toList()
        snapshot.forEach { att ->
            // شريحة بسيطة: اسم الملف + زر إزالة
            val chip = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_input_field)
                setPadding(24, 14, 14, 14)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 12
                layoutParams = lp
            }
            val icon = if (att.isImage()) "🖼" else "📄"
            val tvName = android.widget.TextView(this).apply {
                text = "$icon  ${att.filename}  (${humanSize(att.sizeBytes)})"
                textSize = 12f
                setTextColor(getColor(R.color.bum_text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            chip.addView(tvName)
            val btnRemove = android.widget.TextView(this).apply {
                text = "  ✕"
                textSize = 13f
                setTextColor(getColor(R.color.bum_danger))
                setPadding(20, 0, 0, 0)
                setOnClickListener {
                    // إزالة بالمعرّف (ليس بالفهرس) لتجنّب كراش خارج المدى
                    try { java.io.File(att.encryptedPath).delete() } catch (_: Throwable) {}
                    pendingAttachments.removeAll { it.id == att.id }
                    refreshAttachmentsUi()
                }
            }
            chip.addView(btnRemove)
            binding.layoutAttachments.addView(chip)
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }



    private fun toggleCameraMode() {
        if (currentMode == Mode.TEXT) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) enterCameraMode()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        } else exitCameraMode()
    }

    private fun enterCameraMode() {
        currentMode = Mode.CAMERA
        binding.layoutWriting.visibility = View.GONE
        binding.layoutCameraMode.visibility = View.VISIBLE

        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.etContent.windowToken, 0)

        updateCameraButtons(Mode.CAMERA)
        startCamera()
    }

    private fun exitCameraMode() {
        currentMode = Mode.TEXT
        binding.layoutCameraMode.visibility = View.GONE
        binding.layoutWriting.visibility = View.VISIBLE
        capturedImageFile?.delete()
        capturedImageFile = null
        binding.etImageNote.setText("")
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
    }

    private fun updateCameraButtons(mode: Mode) {
        when (mode) {
            Mode.CAMERA -> {
                binding.cameraPreview.visibility = View.VISIBLE
                binding.ivCapturedPreview.visibility = View.GONE
                binding.layoutImageNote.visibility = View.GONE
                binding.btnCapture.visibility = View.VISIBLE
                binding.btnCancelCamera.visibility = View.VISIBLE
                binding.btnRetake.visibility = View.GONE
                binding.btnSaveImage.visibility = View.GONE
            }
            Mode.CAPTURED_PREVIEW -> {
                binding.cameraPreview.visibility = View.GONE
                binding.ivCapturedPreview.visibility = View.VISIBLE
                binding.layoutImageNote.visibility = View.VISIBLE
                binding.btnCapture.visibility = View.GONE
                binding.btnCancelCamera.visibility = View.VISIBLE
                binding.btnRetake.visibility = View.VISIBLE
                binding.btnSaveImage.visibility = View.VISIBLE
            }
            Mode.TEXT -> { /* غير متعلق */ }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (_: Exception) { showToast(getString(R.string.camera_error)) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val dir = File(filesDir, "captures").apply { mkdirs() }
        val file = File(dir, "bum_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()

        binding.btnCapture.isEnabled = false
        binding.flashOverlay.visibility = View.VISIBLE
        lifecycleScope.launch { delay(100); binding.flashOverlay.visibility = View.GONE }

        capture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedImageFile = file
                    currentMode = Mode.CAPTURED_PREVIEW
                    binding.ivCapturedPreview.setImageURI(Uri.fromFile(file))
                    updateCameraButtons(Mode.CAPTURED_PREVIEW)
                    binding.btnCapture.isEnabled = true
                    binding.etImageNote.requestFocus()
                    lifecycleScope.launch {
                        delay(300)
                        val imm = getSystemService(InputMethodManager::class.java)
                        imm.showSoftInput(binding.etImageNote, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    showToast(getString(R.string.capture_error))
                    binding.btnCapture.isEnabled = true
                }
            })
    }

    private fun retakePhoto() {
        capturedImageFile?.delete()
        capturedImageFile = null
        binding.etImageNote.setText("")
        currentMode = Mode.CAMERA
        updateCameraButtons(Mode.CAMERA)
    }



    private fun showExpiryDialog(onChosen: (Note.ExpiryPolicy, Boolean) -> Unit) {
        if (isFinishing || isDestroyed) return
        val policies = Note.ExpiryPolicy.values()
        val defaultIdx = policies.indexOfFirst { it.name == settings.defaultExpiryPolicyName }
            .coerceAtLeast(1)
        var selected = defaultIdx
        var destroyOnRead = settings.defaultDestroyOnRead

        val view = layoutInflater.inflate(R.layout.dialog_expiry, null)
        val rg = view.findViewById<android.widget.RadioGroup>(R.id.rg_expiry)
        val cbDestroy = view.findViewById<android.widget.CheckBox>(R.id.cb_destroy_on_read)
        cbDestroy.isChecked = destroyOnRead
        policies.forEachIndexed { i, p ->
            val rb = android.widget.RadioButton(this).apply {
                text = p.displayLabel
                id = View.generateViewId()
                textSize = 15f
                setTextColor(getColor(R.color.bum_text_primary))
                setPadding(8, 20, 8, 20)
                isChecked = (i == defaultIdx)
            }
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { group, checkedId ->
            for (i in 0 until group.childCount) {
                if (group.getChildAt(i).id == checkedId) { selected = i; break }
            }
        }
        cbDestroy.setOnCheckedChangeListener { _, v -> destroyOnRead = v }

        val dialog = AlertDialog.Builder(this, R.style.BumAlertDialog)
            .setTitle("كم تريد أن تعيش هذه اللحظة؟")
            .setView(view)
            .setPositiveButton("تم") { d, _ ->
                d.dismiss()
                onChosen(policies[selected], destroyOnRead)
            }
            .setNegativeButton("إلغاء") { d, _ -> d.dismiss() }
            .create()
        ScreenshotBlocker.enable(dialog)
        try { dialog.show() } catch (_: Exception) {}
    }



    private fun showSaveAnimationAndFinish() {
        lifecycleScope.launch {
            binding.layoutSaveSuccess.visibility = View.VISIBLE
            val a = AnimationUtils.loadAnimation(this@WriteActivity, R.anim.scale_bounce)
            binding.layoutSaveSuccess.startAnimation(a)
            delay(900)
            binding.layoutSaveSuccess.visibility = View.GONE
            finish()
            overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down_fade)
        }
    }

    private fun shakeInput() {
        binding.etContent.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake))
    }


    private fun updateGhostToggleUi() {
        if (isGhostMode) {
            binding.btnGhostToggle.setBackgroundResource(R.drawable.bg_ghost_toggle_active)
            binding.btnGhostToggle.setColorFilter(getColor(R.color.bum_accent))
        } else {
            binding.btnGhostToggle.setBackgroundResource(R.drawable.bg_ghost_toggle)
            binding.btnGhostToggle.setColorFilter(getColor(R.color.bum_text_secondary))
        }
    }

    private fun animateEntrance() {
        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(160).start()
    }

    private fun showToast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        // إذا المستخدم خرج بدون حفظ → نحذف المرفقات المشفّرة التي كانت pending
        pendingAttachments.forEach {
            try { File(it.encryptedPath).delete() } catch (_: Throwable) {}
        }
        pendingAttachments.clear()
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Throwable) {}
        try {
            if (!cameraExecutor.isShutdown) cameraExecutor.shutdown()
        } catch (_: Throwable) {}
    }
}
