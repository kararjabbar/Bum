package com.bum.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bum.app.BumApplication
import com.bum.app.R
import com.bum.app.databinding.ActivityBiometricBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// شاشة المصادقة البيومترية
class BiometricActivity : BaseSecureActivity() {

    private lateinit var binding: ActivityBiometricBinding
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBiometricBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBiometric()
        setupUI()

        lifecycleScope.launch {
            delay(500)
            showBiometricPrompt()
        }
    }

    private fun setupUI() {
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_scale)
        binding.ivLockIcon.startAnimation(fadeIn)
        binding.tvAuthTitle.startAnimation(fadeIn)
        binding.btnAuthenticate.setOnClickListener { showBiometricPrompt() }
    }

    private fun setupBiometric() {
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showError(errString.toString())
                }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthSuccess()
                }
                override fun onAuthenticationFailed() { shakeIcon() }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(false)
            .build()
    }

    private fun showBiometricPrompt() {
        val bm = BiometricManager.from(this)
        when (bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> biometricPrompt.authenticate(promptInfo)
            else -> onAuthSuccess() // الجهاز لا يدعم — نسمح بالدخول
        }
    }

    private fun onAuthSuccess() {
        BumApplication.lifecycleObserver.markAuthenticated()
        lifecycleScope.launch {
            binding.ivLockIcon.setImageResource(R.drawable.ic_unlock)
            val scaleAnim = AnimationUtils.loadAnimation(this@BiometricActivity, R.anim.scale_bounce)
            binding.ivLockIcon.startAnimation(scaleAnim)
            delay(350)
            startActivity(Intent(this@BiometricActivity, MainActivity::class.java))
            overridePendingTransition(R.anim.slide_up_fade, android.R.anim.fade_out)
            finish()
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
        lifecycleScope.launch { delay(3000); binding.tvError.visibility = View.GONE }
    }

    private fun shakeIcon() {
        binding.ivLockIcon.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake))
    }
}
