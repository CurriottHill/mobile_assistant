package com.example.mobile_assistant

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.mobile_assistant.auth.AuthCoordinator
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEMORY_ONLY = "com.example.mobile_assistant.extra.MEMORY_ONLY"
        const val EXTRA_INITIAL_STEP = "com.example.mobile_assistant.extra.INITIAL_STEP"

        const val STEP_AUTH_WELCOME = 0
        const val STEP_AUTH_EMAIL = 1
        const val STEP_MEMORY = 2
        const val STEP_ACCESSIBILITY = 3
        const val STEP_ASSISTANT = 4
        const val STEP_PERMISSIONS = 5
        const val STEP_LOCATION = 6
        const val STEP_SPOTIFY = 7
        const val STEP_DONE = 8
    }

    private lateinit var flipper: ViewFlipper
    private lateinit var progress: ProgressBar
    private lateinit var stepLabel: TextView

    private lateinit var accessibilityStatus: TextView
    private lateinit var accessibilityStatusDot: View
    private lateinit var accessibilityContinue: MaterialButton
    private lateinit var assistantStatus: TextView
    private lateinit var assistantStatusDot: View
    private lateinit var assistantContinue: MaterialButton
    private lateinit var permissionsStatus: TextView
    private lateinit var permissionsStatusDot: View
    private lateinit var permissionsContinue: MaterialButton
    private lateinit var locationStatus: TextView
    private lateinit var locationStatusDot: View
    private lateinit var locationContinue: MaterialButton
    private lateinit var spotifyStatus: TextView
    private lateinit var spotifyStatusDot: View
    private lateinit var spotifyAction: MaterialButton
    private lateinit var spotifyContinue: MaterialButton
    private lateinit var nameInput: EditText
    private lateinit var personalityInput: EditText
    private lateinit var homeInput: EditText
    private lateinit var workInput: EditText
    private lateinit var defaultMessagingInput: EditText

    // Auth-step views
    private lateinit var authGoogleButton: MaterialButton
    private lateinit var authEmailButton: MaterialButton
    private lateinit var authSkipButton: MaterialButton
    private lateinit var authErrorText: TextView
    private lateinit var authConsentText: TextView

    private lateinit var emailSegRegister: TextView
    private lateinit var emailSegSignIn: TextView
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var emailErrorText: TextView
    private lateinit var emailSubmitButton: MaterialButton
    private lateinit var emailBackButton: MaterialButton

    private var registerMode: Boolean = true
    private var authInFlight: Boolean = false

    private val prefs by lazy { getSharedPreferences("marvin_prefs", MODE_PRIVATE) }
    private val spotifyService by lazy { SpotifyService(applicationContext) }
    private val googleAccountService by lazy { GoogleAccountService(applicationContext) }
    private val memoryOnboardingManager by lazy {
        MemoryOnboardingManager(MemoryRepository(applicationContext))
    }
    private val memoryOnly: Boolean
        get() = intent?.getBooleanExtra(EXTRA_MEMORY_ONLY, false) == true

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshCurrentStep()
        }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleGoogleSignInResult(result.data) }

    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> goToStep(STEP_MEMORY) }

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
        flipper.inAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_in_right)
        flipper.outAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_out_left)

        bindAuthViews()
        bindStepViews()

        bindAuthWelcomeStep()
        bindAuthEmailStep()
        bindMemoryStep()
        bindAccessibilityStep()
        bindAssistantStep()
        bindPermissionsStep()
        bindLocationStep()
        bindSpotifyStep()
        findViewById<MaterialButton>(R.id.btnOnboardingFinish).setOnClickListener { finishOnboarding() }

        populateMemoryForm()
        flipper.displayedChild = pickInitialStep()
        updateChrome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!memoryOnly) {
            initialStepFromIntent(intent)?.let {
                goToStep(if (isSignedIn()) it.coerceAtLeast(STEP_MEMORY) else it)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isSignedIn() && flipper.displayedChild >= STEP_MEMORY) {
            goToStep(STEP_AUTH_WELCOME)
            showAuthError(getString(R.string.account_sign_in_required))
            return
        }
        refreshCurrentStep()
    }

    private fun bindAuthViews() {
        authGoogleButton = findViewById(R.id.btnAuthGoogle)
        authEmailButton = findViewById(R.id.btnAuthEmail)
        authSkipButton = findViewById(R.id.btnAuthSkip)
        authErrorText = findViewById(R.id.textOnboardingAuthError)
        authConsentText = findViewById(R.id.textOnboardingConsent)

        emailSegRegister = findViewById(R.id.segAuthRegister)
        emailSegSignIn = findViewById(R.id.segAuthSignIn)
        emailInput = findViewById(R.id.inputAuthEmail)
        passwordInput = findViewById(R.id.inputAuthPassword)
        confirmPasswordInput = findViewById(R.id.inputAuthConfirmPassword)
        emailErrorText = findViewById(R.id.textAuthEmailError)
        emailSubmitButton = findViewById(R.id.btnAuthEmailSubmit)
        emailBackButton = findViewById(R.id.btnAuthEmailBack)
    }

    private fun bindStepViews() {
        accessibilityStatus = findViewById(R.id.textOnboardingAccessibilityStatus)
        accessibilityStatusDot = findViewById(R.id.dotOnboardingAccessibility)
        accessibilityContinue = findViewById(R.id.btnOnboardingAccessibilityContinue)
        assistantStatus = findViewById(R.id.textOnboardingAssistantStatus)
        assistantStatusDot = findViewById(R.id.dotOnboardingAssistant)
        assistantContinue = findViewById(R.id.btnOnboardingAssistantContinue)
        permissionsStatus = findViewById(R.id.textOnboardingPermissionsStatus)
        permissionsStatusDot = findViewById(R.id.dotOnboardingPermissions)
        permissionsContinue = findViewById(R.id.btnOnboardingPermissionsContinue)
        locationStatus = findViewById(R.id.textOnboardingLocationStatus)
        locationStatusDot = findViewById(R.id.dotOnboardingLocation)
        locationContinue = findViewById(R.id.btnOnboardingLocationContinue)
        spotifyStatus = findViewById(R.id.textOnboardingSpotifyStatus)
        spotifyStatusDot = findViewById(R.id.dotOnboardingSpotify)
        spotifyAction = findViewById(R.id.btnOnboardingSpotifyAction)
        spotifyContinue = findViewById(R.id.btnOnboardingSpotifyContinue)
        nameInput = findViewById(R.id.inputOnboardingName)
        personalityInput = findViewById(R.id.inputOnboardingPersonality)
        homeInput = findViewById(R.id.inputOnboardingHome)
        workInput = findViewById(R.id.inputOnboardingWork)
        defaultMessagingInput = findViewById(R.id.inputOnboardingDefaultMessaging)
    }

    private fun pickInitialStep(): Int {
        if (!isSignedIn()) return STEP_AUTH_WELCOME
        if (memoryOnly) return STEP_MEMORY
        initialStepFromIntent(intent)?.let { return it.coerceAtLeast(STEP_MEMORY) }
        return STEP_MEMORY
    }

    private fun bindAuthWelcomeStep() {
        val raw = getString(R.string.onboarding_auth_consent_inline,
            getString(R.string.onboarding_consent_terms),
            getString(R.string.onboarding_consent_privacy))
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
        authConsentText.text = spannable
        authConsentText.movementMethod = LinkMovementMethod.getInstance()

        authGoogleButton.isEnabled = FirebaseAuthSupport.isGoogleSignInConfigured()
        authGoogleButton.setOnClickListener { startGoogleSignIn() }
        authEmailButton.setOnClickListener {
            clearAuthError()
            goToStep(STEP_AUTH_EMAIL)
        }
        authSkipButton.setOnClickListener {
            showAuthError(getString(R.string.account_sign_in_required))
        }
    }

    private fun bindAuthEmailStep() {
        applyEmailModeStyling()
        emailSegRegister.setOnClickListener {
            registerMode = true
            applyEmailModeStyling()
        }
        emailSegSignIn.setOnClickListener {
            registerMode = false
            applyEmailModeStyling()
        }
        emailSubmitButton.setOnClickListener { submitEmailAuth() }
        emailBackButton.setOnClickListener {
            clearEmailError()
            goToStep(STEP_AUTH_WELCOME)
        }
    }

    private fun applyEmailModeStyling() {
        if (registerMode) {
            emailSegRegister.setBackgroundResource(R.drawable.bg_onboarding_seg_active)
            emailSegRegister.setTextColor(getColor(R.color.home_button_primary_text))
            emailSegSignIn.setBackgroundResource(R.drawable.bg_onboarding_seg_inactive)
            emailSegSignIn.setTextColor(getColor(R.color.home_panel_body))
            confirmPasswordInput.visibility = View.VISIBLE
            emailSubmitButton.setText(R.string.onboarding_auth_submit_register)
        } else {
            emailSegSignIn.setBackgroundResource(R.drawable.bg_onboarding_seg_active)
            emailSegSignIn.setTextColor(getColor(R.color.home_button_primary_text))
            emailSegRegister.setBackgroundResource(R.drawable.bg_onboarding_seg_inactive)
            emailSegRegister.setTextColor(getColor(R.color.home_panel_body))
            confirmPasswordInput.visibility = View.GONE
            emailSubmitButton.setText(R.string.onboarding_auth_submit_signin)
        }
    }

    private fun submitEmailAuth() {
        if (authInFlight) return
        clearEmailError()
        val email = emailInput.text?.toString().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        val confirm = if (registerMode) confirmPasswordInput.text?.toString().orEmpty() else null

        val validation = AuthCoordinator.validateEmailPassword(email, password, confirm)
        if (validation != null) {
            showEmailError(validation)
            return
        }
        if (!FirebaseAuthSupport.isConfigured()) {
            showEmailError(getString(R.string.account_firebase_not_configured))
            return
        }
        setEmailAuthLoading(true)
        lifecycleScope.launch {
            val result = if (registerMode) {
                AuthCoordinator.signUpWithEmail(this@OnboardingActivity, email, password)
            } else {
                AuthCoordinator.signInWithEmail(this@OnboardingActivity, email, password)
            }
            setEmailAuthLoading(false)
            result
                .onSuccess { onAuthSucceeded(googleMode = false) }
                .onFailure { showEmailError(AuthCoordinator.describeAuthError(it)) }
        }
    }

    private fun setEmailAuthLoading(loading: Boolean) {
        authInFlight = loading
        emailSubmitButton.isEnabled = !loading
        emailSubmitButton.text = if (loading) {
            getString(R.string.onboarding_auth_signing_in)
        } else if (registerMode) {
            getString(R.string.onboarding_auth_submit_register)
        } else {
            getString(R.string.onboarding_auth_submit_signin)
        }
    }

    private fun showEmailError(message: String) {
        emailErrorText.text = message
        emailErrorText.visibility = View.VISIBLE
    }

    private fun clearEmailError() {
        emailErrorText.visibility = View.GONE
        emailErrorText.text = ""
    }

    private fun showAuthError(message: String) {
        authErrorText.text = message
        authErrorText.visibility = View.VISIBLE
    }

    private fun clearAuthError() {
        authErrorText.visibility = View.GONE
        authErrorText.text = ""
    }

    private fun startGoogleSignIn() {
        if (authInFlight) return
        clearAuthError()
        if (!FirebaseAuthSupport.ensureInitialized(this)) {
            showAuthError(getString(R.string.account_firebase_not_configured))
            return
        }
        if (!FirebaseAuthSupport.isGoogleSignInConfigured()) {
            showAuthError(getString(R.string.account_google_not_configured))
            return
        }
        authInFlight = true
        authGoogleButton.isEnabled = false
        authGoogleButton.text = getString(R.string.onboarding_auth_signing_in)
        val client = FirebaseAuthSupport.googleSignInClient(this)
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun handleGoogleSignInResult(data: Intent?) {
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        }.getOrElse {
            resetGoogleButton()
            showAuthError(FirebaseAuthSupport.describeGoogleSignInError(it))
            return
        }
        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            resetGoogleButton()
            showAuthError(getString(R.string.account_google_missing_token))
            return
        }
        lifecycleScope.launch {
            val signInResult = runCatching {
                FirebaseAuthSupport.signInWithGoogleIdToken(this@OnboardingActivity, idToken)
            }
            resetGoogleButton()
            signInResult
                .onSuccess { onAuthSucceeded(googleMode = true) }
                .onFailure { showAuthError(FirebaseAuthSupport.describeGoogleSignInError(it)) }
        }
    }

    private fun resetGoogleButton() {
        authInFlight = false
        authGoogleButton.isEnabled = FirebaseAuthSupport.isGoogleSignInConfigured()
        authGoogleButton.setText(R.string.onboarding_auth_google)
    }

    private fun onAuthSucceeded(googleMode: Boolean) {
        prefs.edit().putBoolean("consent_accepted", true).apply()
        if (googleMode) {
            val intent = googleAccountService.createLoginIntent().intent
            if (intent != null) {
                googleAuthorizationLauncher.launch(intent)
                return
            }
        }
        goToStep(STEP_MEMORY)
    }

    private fun bindMemoryStep() {
        findViewById<MaterialButton>(R.id.btnOnboardingMemoryContinue).setOnClickListener {
            saveMemoryForm()
            if (memoryOnly) {
                finish()
            } else {
                goToStep(STEP_ACCESSIBILITY)
            }
        }
    }

    private fun bindAccessibilityStep() {
        findViewById<MaterialButton>(R.id.btnOnboardingAccessibilityAction).setOnClickListener {
            SetupChecks.openAccessibilitySettings(this)
        }
        accessibilityContinue.setOnClickListener {
            if (SetupChecks.isAccessibilityEnabled(this)) goToStep(STEP_ASSISTANT) else refreshCurrentStep()
        }
        findViewById<MaterialButton>(R.id.btnOnboardingAccessibilitySkip).setOnClickListener {
            goToStep(STEP_ASSISTANT)
        }
    }

    private fun bindAssistantStep() {
        findViewById<MaterialButton>(R.id.btnOnboardingAssistantAction).setOnClickListener {
            SetupChecks.openDefaultAssistantSettings(this)
        }
        assistantContinue.setOnClickListener {
            if (SetupChecks.isDefaultAssistantApp(this)) goToStep(STEP_PERMISSIONS) else refreshCurrentStep()
        }
        findViewById<MaterialButton>(R.id.btnOnboardingAssistantSkip).setOnClickListener {
            goToStep(STEP_PERMISSIONS)
        }
    }

    private fun bindPermissionsStep() {
        findViewById<MaterialButton>(R.id.btnOnboardingPermissionsAction).setOnClickListener {
            SetupChecks.requestMissingPermissions(this, requestPermissions)
        }
        findViewById<MaterialButton>(R.id.btnOnboardingPermissionsAppSettings).setOnClickListener {
            SetupChecks.openAppDetailsSettings(this)
        }
        permissionsContinue.setOnClickListener {
            if (SetupChecks.missingRuntimePermissionLabels(this).isEmpty()) {
                goToStep(STEP_LOCATION)
            } else {
                refreshCurrentStep()
            }
        }
        findViewById<MaterialButton>(R.id.btnOnboardingPermissionsSkip).setOnClickListener {
            goToStep(STEP_LOCATION)
        }
    }

    private fun bindLocationStep() {
        findViewById<MaterialButton>(R.id.btnOnboardingLocationPermissionAction).setOnClickListener {
            SetupChecks.requestLocationPermissions(this, requestPermissions)
        }
        findViewById<MaterialButton>(R.id.btnOnboardingLocationPreferencesAction).setOnClickListener {
            SetupChecks.openLocationSettings(this)
        }
        locationContinue.setOnClickListener {
            if (SetupChecks.isLocationReady(this)) {
                goToStep(STEP_SPOTIFY)
            } else {
                refreshCurrentStep()
            }
        }
        findViewById<MaterialButton>(R.id.btnOnboardingLocationSkip).setOnClickListener {
            goToStep(STEP_SPOTIFY)
        }
    }

    private fun bindSpotifyStep() {
        spotifyAction.setOnClickListener {
            SetupChecks.launchSpotifyLogin(this, spotifyService)
        }
        spotifyContinue.setOnClickListener {
            if (spotifyService.connectionStatus().isConnected) {
                goToStep(STEP_DONE)
            } else {
                refreshCurrentStep()
            }
        }
        findViewById<MaterialButton>(R.id.btnOnboardingSpotifySkip).setOnClickListener {
            goToStep(STEP_DONE)
        }
    }

    private fun goToStep(index: Int) {
        val target = gatedStep(index.coerceIn(0, flipper.childCount - 1))
        val current = flipper.displayedChild
        if (target > current) {
            flipper.inAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_in_right)
            flipper.outAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_out_left)
        } else if (target < current) {
            flipper.inAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_in_left)
            flipper.outAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_out_right)
        }
        flipper.displayedChild = target
        updateChrome()
        refreshCurrentStep()
    }

    private fun initialStepFromIntent(intent: Intent?): Int? {
        val step = intent?.getIntExtra(EXTRA_INITIAL_STEP, -1) ?: -1
        return step.takeIf { it in 0 until flipper.childCount }
    }

    private fun gatedStep(step: Int): Int {
        return if (!isSignedIn() && step >= STEP_MEMORY) STEP_AUTH_WELCOME else step
    }

    private fun isSignedIn(): Boolean = AuthCoordinator.currentUser(this) != null

    private fun updateChrome() {
        if (memoryOnly) {
            stepLabel.text = getString(R.string.onboarding_memory_title)
            progress.progress = 100
            return
        }
        val total = flipper.childCount
        val current = flipper.displayedChild
        stepLabel.text = getString(R.string.onboarding_step_label, current + 1, total)
        progress.progress = (current + 1) * 100 / total
    }

    /** Re-check the step currently shown (called on resume + after returning from system Settings). */
    private fun refreshCurrentStep() {
        when (flipper.displayedChild) {
            STEP_ACCESSIBILITY -> {
                val ok = SetupChecks.isAccessibilityEnabled(this)
                accessibilityStatus.text = getString(
                    if (ok) R.string.accessibility_status_ready else R.string.accessibility_status_missing
                )
                applyStatusDot(accessibilityStatusDot, ok)
                accessibilityContinue.isEnabled = ok
            }
            STEP_ASSISTANT -> {
                val ok = SetupChecks.isDefaultAssistantApp(this)
                assistantStatus.text = getString(
                    if (ok) R.string.default_assistant_status_ready else R.string.default_assistant_status_missing
                )
                applyStatusDot(assistantStatusDot, ok)
                assistantContinue.isEnabled = ok
            }
            STEP_PERMISSIONS -> {
                val missing = SetupChecks.missingRuntimePermissionLabels(this)
                val ok = missing.isEmpty()
                permissionsStatus.text = if (ok) {
                    getString(R.string.permissions_status_ready)
                } else {
                    getString(R.string.permissions_status_missing, missing.joinToString())
                }
                applyStatusDot(permissionsStatusDot, ok)
                permissionsContinue.isEnabled = ok
            }
            STEP_LOCATION -> {
                val ok = SetupChecks.isLocationReady(this)
                locationStatus.text = SetupChecks.locationStatusLabel(this)
                applyStatusDot(locationStatusDot, ok)
                locationContinue.isEnabled = ok
            }
            STEP_SPOTIFY -> {
                val connection = spotifyService.connectionStatus()
                spotifyStatus.text = connection.statusText
                spotifyAction.text = getString(
                    if (connection.isConnected) R.string.spotify_reconnect else R.string.spotify_connect
                )
                spotifyAction.isEnabled = connection.isConfigured
                applyStatusDot(spotifyStatusDot, connection.isConnected)
                spotifyContinue.isEnabled = connection.isConnected
            }
        }
    }

    private fun applyStatusDot(dot: View, ok: Boolean) {
        dot.setBackgroundResource(
            if (ok) R.drawable.bg_status_dot_ok else R.drawable.bg_status_dot_pending
        )
    }

    private fun saveMemoryForm() {
        memoryOnboardingManager.completeFromForm(
            name = nameInput.text?.toString().orEmpty(),
            personality = personalityInput.text?.toString().orEmpty(),
            home = homeInput.text?.toString().orEmpty(),
            work = workInput.text?.toString().orEmpty(),
            defaultMessagingApp = defaultMessagingInput.text?.toString().orEmpty()
        )
    }

    private fun populateMemoryForm() {
        val snapshot = MemoryRepository(applicationContext).promptSnapshot()
        nameInput.setText(lineValue(snapshot.mainMarkdown, "Name"))
        homeInput.setText(lineValue(snapshot.mainMarkdown, "Home"))
        workInput.setText(lineValue(snapshot.mainMarkdown, "Work"))
        defaultMessagingInput.setText(lineValue(snapshot.mainMarkdown, "Default messaging app"))
        personalityInput.setText(
            snapshot.soulMarkdown
                .lineSequence()
                .dropWhile { it.trim().equals("# Soul", ignoreCase = true) || it.isBlank() }
                .joinToString("\n")
                .trim()
        )
    }

    private fun lineValue(markdown: String, label: String): String {
        return Regex("""(?m)^${Regex.escape(label)}:[ \t]*(.*)$""")
            .find(markdown)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun finishOnboarding() {
        if (!isSignedIn()) {
            goToStep(STEP_AUTH_WELCOME)
            showAuthError(getString(R.string.account_sign_in_required))
            return
        }
        if (memoryOnboardingManager.needsOnboarding()) {
            saveMemoryForm()
        }
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
