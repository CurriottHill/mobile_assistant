package com.example.mobile_assistant

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class OnboardingActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var progress: ProgressBar
    private lateinit var stepLabel: TextView

    private lateinit var accessibilityStatus: TextView
    private lateinit var accessibilityContinue: Button
    private lateinit var assistantStatus: TextView
    private lateinit var assistantContinue: Button
    private lateinit var permissionsStatus: TextView
    private lateinit var permissionsContinue: Button
    private lateinit var locationStatus: TextView
    private lateinit var locationContinue: Button

    private val prefs by lazy { getSharedPreferences("aura_prefs", MODE_PRIVATE) }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshCurrentStep()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboarding)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_onboarding)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        flipper = findViewById(R.id.onboardingFlipper)
        progress = findViewById(R.id.onboardingProgress)
        stepLabel = findViewById(R.id.textOnboardingStepLabel)
        flipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        flipper.outAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)

        accessibilityStatus = findViewById(R.id.textOnboardingAccessibilityStatus)
        accessibilityContinue = findViewById(R.id.btnOnboardingAccessibilityContinue)
        assistantStatus = findViewById(R.id.textOnboardingAssistantStatus)
        assistantContinue = findViewById(R.id.btnOnboardingAssistantContinue)
        permissionsStatus = findViewById(R.id.textOnboardingPermissionsStatus)
        permissionsContinue = findViewById(R.id.btnOnboardingPermissionsContinue)
        locationStatus = findViewById(R.id.textOnboardingLocationStatus)
        locationContinue = findViewById(R.id.btnOnboardingLocationContinue)

        bindConsentStep()
        bindAccessibilityStep()
        bindAssistantStep()
        bindPermissionsStep()
        bindLocationStep()
        findViewById<Button>(R.id.btnOnboardingFinish).setOnClickListener { finishOnboarding() }

        updateChrome()
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentStep()
    }

    private fun bindConsentStep() {
        val raw = getString(R.string.onboarding_consent_text)
        val termsLabel = getString(R.string.onboarding_consent_terms)
        val privacyLabel = getString(R.string.onboarding_consent_privacy)
        val spannable = SpannableString(raw)

        raw.indexOf(termsLabel).takeIf { it >= 0 }?.let { start ->
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(this@OnboardingActivity, TermsActivity::class.java))
                }
            }, start, start + termsLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        raw.indexOf(privacyLabel).takeIf { it >= 0 }?.let { start ->
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(this@OnboardingActivity, PrivacyPolicyActivity::class.java))
                }
            }, start, start + privacyLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        findViewById<TextView>(R.id.textOnboardingConsent).apply {
            text = spannable
            movementMethod = LinkMovementMethod.getInstance()
        }

        findViewById<Button>(R.id.btnConsentAccept).setOnClickListener {
            prefs.edit().putBoolean("consent_accepted", true).apply()
            goToStep(1)
        }
        findViewById<Button>(R.id.btnConsentDecline).setOnClickListener { finishAffinity() }
    }

    private fun bindAccessibilityStep() {
        findViewById<Button>(R.id.btnOnboardingAccessibilityAction).setOnClickListener {
            SetupChecks.openAccessibilitySettings(this)
        }
        accessibilityContinue.setOnClickListener {
            if (SetupChecks.isAccessibilityEnabled(this)) goToStep(2) else refreshCurrentStep()
        }
        findViewById<Button>(R.id.btnOnboardingAccessibilitySkip).setOnClickListener { goToStep(2) }
    }

    private fun bindAssistantStep() {
        findViewById<Button>(R.id.btnOnboardingAssistantAction).setOnClickListener {
            SetupChecks.openDefaultAssistantSettings(this)
        }
        assistantContinue.setOnClickListener {
            if (SetupChecks.isDefaultAssistantApp(this)) goToStep(3) else refreshCurrentStep()
        }
        findViewById<Button>(R.id.btnOnboardingAssistantSkip).setOnClickListener { goToStep(3) }
    }

    private fun bindPermissionsStep() {
        findViewById<Button>(R.id.btnOnboardingPermissionsAction).setOnClickListener {
            SetupChecks.requestMissingPermissions(this, requestPermissions)
        }
        findViewById<Button>(R.id.btnOnboardingPermissionsAppSettings).setOnClickListener {
            SetupChecks.openAppDetailsSettings(this)
        }
        permissionsContinue.setOnClickListener {
            if (SetupChecks.missingRuntimePermissionLabels(this).isEmpty()) {
                goToStep(4)
            } else {
                refreshCurrentStep()
            }
        }
        findViewById<Button>(R.id.btnOnboardingPermissionsSkip).setOnClickListener { goToStep(4) }
    }

    private fun bindLocationStep() {
        findViewById<Button>(R.id.btnOnboardingLocationPermissionAction).setOnClickListener {
            SetupChecks.requestLocationPermissions(this, requestPermissions)
        }
        findViewById<Button>(R.id.btnOnboardingLocationPreferencesAction).setOnClickListener {
            SetupChecks.openLocationSettings(this)
        }
        locationContinue.setOnClickListener {
            if (SetupChecks.isLocationReady(this)) {
                goToStep(5)
            } else {
                refreshCurrentStep()
            }
        }
        findViewById<Button>(R.id.btnOnboardingLocationSkip).setOnClickListener { goToStep(5) }
    }

    private fun goToStep(index: Int) {
        flipper.displayedChild = index
        updateChrome()
        refreshCurrentStep()
    }

    private fun updateChrome() {
        val total = flipper.childCount
        val current = flipper.displayedChild
        stepLabel.text = getString(R.string.onboarding_step_label, current + 1, total)
        progress.progress = (current + 1) * 100 / total
    }

    /** Re-check the step currently shown (called on resume + after returning from system Settings). */
    private fun refreshCurrentStep() {
        when (flipper.displayedChild) {
            1 -> {
                val ok = SetupChecks.isAccessibilityEnabled(this)
                accessibilityStatus.text = getString(
                    if (ok) R.string.accessibility_status_ready else R.string.accessibility_status_missing
                )
                accessibilityContinue.isEnabled = ok
            }
            2 -> {
                val ok = SetupChecks.isDefaultAssistantApp(this)
                assistantStatus.text = getString(
                    if (ok) R.string.default_assistant_status_ready else R.string.default_assistant_status_missing
                )
                assistantContinue.isEnabled = ok
            }
            3 -> {
                val missing = SetupChecks.missingRuntimePermissionLabels(this)
                permissionsStatus.text = if (missing.isEmpty()) {
                    getString(R.string.permissions_status_ready)
                } else {
                    getString(R.string.permissions_status_missing, missing.joinToString())
                }
                permissionsContinue.isEnabled = missing.isEmpty()
            }
            4 -> {
                val ok = SetupChecks.isLocationReady(this)
                locationStatus.text = SetupChecks.locationStatusLabel(this)
                locationContinue.isEnabled = ok
            }
        }
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
