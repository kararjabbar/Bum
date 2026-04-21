package com.bum.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.lifecycle.lifecycleScope
import com.bum.app.R
import com.bum.app.databinding.ActivitySplashBinding
import com.bum.app.security.RootDetector
import com.bum.app.utils.ThemeGlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// شاشة البداية
class SplashActivity : BaseSecureActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeGlow.applySplashGlow(binding.viewGlowBg, this)

        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        startSplashSequence()
    }

    private fun startSplashSequence() {
        lifecycleScope.launch {
                val fadeIn = AnimationUtils.loadAnimation(this@SplashActivity, R.anim.fade_in_scale)
            binding.tvLogo.startAnimation(fadeIn)

            delay(800)

            val slideUp = AnimationUtils.loadAnimation(this@SplashActivity, R.anim.slide_up_fade)
            binding.tvTagline.startAnimation(slideUp)
            binding.tvTagline.visibility = android.view.View.VISIBLE

            delay(600)

            val securityPassed = performSecurityChecks()

            delay(800)

            if (securityPassed) {
                navigateToMain()
            } else {
                showSecurityWarning()
            }
        }
    }

    private fun performSecurityChecks(): Boolean {
        val isRooted = RootDetector.isDeviceRooted()
        val isEmulator = RootDetector.isEmulator()

        return if (isRooted && !isDebugBuild()) {
            false
        } else {
            true
        }
    }

    private fun isDebugBuild(): Boolean {
        return com.bum.app.BuildConfig.DEBUG
    }

    private fun navigateToMain() {
        val intent = Intent(this, BiometricActivity::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in_scale, android.R.anim.fade_out)
        finish()
    }

    private fun showSecurityWarning() {
        binding.tvTagline.text = getString(R.string.security_warning)
        val tv = android.util.TypedValue()
        theme.resolveAttribute(com.bum.app.R.attr.bumDanger, tv, true)
        binding.tvTagline.setTextColor(tv.data)

        lifecycleScope.launch {
            delay(2000)
            finishAffinity()
        }
    }
}
