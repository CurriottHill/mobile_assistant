package com.example.mobile_assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.mobile_assistant.auth.AuthCoordinator
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val spotifyService by lazy { SpotifyService(applicationContext) }
    private val googleAccountService by lazy { GoogleAccountService(applicationContext) }
    private val backendClient by lazy { MobileBackendClient(applicationContext) }
    private val prefs by lazy { getSharedPreferences("marvin_prefs", MODE_PRIVATE) }

    private lateinit var textReadyStatus: TextView
    private lateinit var homeGoogleLoginButton: Button

    private val marvinGoogleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleMarvinGoogleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isSpotifyRedirect = spotifyService.isSpotifyRedirect(intent?.data)
        val onboardingDone = prefs.getBoolean("onboarding_complete", false)
        if (!isSignedIn()) {
            startActivity(loginIntent())
            finish()
            return
        }
        if (!onboardingDone && !isSpotifyRedirect) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        textReadyStatus = findViewById(R.id.textReadyStatus)
        homeGoogleLoginButton = findViewById(R.id.btnHomeGoogleLogin)
        bindStaticClickListeners()
        refreshDashboard()
        maybeHandleSpotifyRedirect(intent, returnToOnboarding = !onboardingDone)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isSignedIn()) {
            startActivity(loginIntent())
            finish()
            return
        }
        val onboardingDone = prefs.getBoolean("onboarding_complete", false)
        maybeHandleSpotifyRedirect(intent, returnToOnboarding = !onboardingDone)
    }

    override fun onResume() {
        super.onResume()
        if (!isSignedIn()) {
            startActivity(loginIntent())
            finish()
            return
        }
        refreshDashboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun bindStaticClickListeners() {
        findViewById<View>(R.id.btnOpenAssistant).setOnClickListener {
            if (isSignedIn()) {
                startActivity(Intent(this, AssistantActivity::class.java))
            } else {
                startActivity(loginIntent())
            }
        }
        findViewById<View>(R.id.btnPreferences).setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }
        homeGoogleLoginButton.setOnClickListener {
            startMarvinGoogleSignIn()
        }
        findViewById<TextView>(R.id.btnTerms).setOnClickListener {
            startActivity(Intent(this, TermsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnPrivacy).setOnClickListener {
            startActivity(Intent(this, PrivacyPolicyActivity::class.java))
        }
        findViewById<TextView>(R.id.btnEval).setOnClickListener {
            startActivity(Intent(this, EvalActivity::class.java))
        }
    }

    private fun refreshDashboard() {
        val accessibilityOk = SetupChecks.isAccessibilityEnabled(this)
        val assistantOk = SetupChecks.isDefaultAssistantApp(this)
        val permissionsOk = SetupChecks.missingRuntimePermissionLabels(this).isEmpty()
        val locationOk = SetupChecks.isLocationReady(this)
        val spotifyStatus = spotifyService.connectionStatus()
        val googleStatus = googleAccountService.connectionStatus()

        bindRow(
            R.id.rowAccessibility,
            getString(R.string.row_accessibility_title),
            accessibilityOk,
            AccessibilitySettingsActivity::class.java
        )
        bindRow(
            R.id.rowDefaultAssistant,
            getString(R.string.row_default_assistant_title),
            assistantOk,
            DefaultAssistantSettingsActivity::class.java
        )
        bindRow(
            R.id.rowPermissions,
            getString(R.string.row_permissions_title),
            permissionsOk,
            PermissionsSettingsActivity::class.java
        )
        bindRow(
            R.id.rowLocation,
            getString(R.string.row_location_title),
            locationOk,
            LocationSettingsActivity::class.java
        )
        bindMemoryRow()
        bindRow(
            R.id.rowSpotify,
            getString(R.string.row_spotify_title),
            spotifyStatus.isConnected && spotifyStatus.hasPlaylistModify,
            SpotifySettingsActivity::class.java
        )
        bindRow(
            R.id.rowGoogle,
            getString(R.string.row_google_title),
            googleStatus.isConnected,
            GoogleSettingsActivity::class.java
        )
        bindAccountRow()

        val attention = listOf(accessibilityOk, assistantOk, permissionsOk, locationOk).count { !it }
        textReadyStatus.text = if (attention == 0) {
            getString(R.string.dashboard_summary_all_set)
        } else {
            getString(R.string.dashboard_summary_attention, attention)
        }
    }

    private fun bindAccountRow() {
        val configured = FirebaseAuthSupport.ensureInitialized(this)
        val user = if (configured) FirebaseAuthSupport.auth(this).currentUser else null
        bindHomeGoogleLogin(user == null)
        bindRow(
            R.id.rowAccount,
            getString(R.string.row_account_title),
            user != null,
            AccountActivity::class.java
        )

        if (user != null) {
            activityScope.launch {
                val summary = runCatching { backendClient.accountSummary() }.getOrNull()
                val row = findViewById<View>(R.id.rowAccount)
                row.findViewById<TextView>(R.id.rowStatus).text = summary?.let {
                    "${it.plan.replaceFirstChar { ch -> ch.titlecase() }} · ${it.remainingCreditsThisMonth}/${it.monthlyCreditLimit} credits"
                } ?: getString(R.string.status_ok)
            }
        }
    }

    private fun bindHomeGoogleLogin(show: Boolean) {
        homeGoogleLoginButton.visibility = if (show) View.VISIBLE else View.GONE
        val googleConfigured = FirebaseAuthSupport.isGoogleSignInConfigured()
        homeGoogleLoginButton.isEnabled = googleConfigured
        homeGoogleLoginButton.text = getString(
            if (googleConfigured) R.string.account_google_continue else R.string.account_google_unavailable
        )
    }

    private fun startMarvinGoogleSignIn() {
        if (!FirebaseAuthSupport.ensureInitialized(this)) {
            Toast.makeText(this, R.string.account_firebase_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        if (!FirebaseAuthSupport.isGoogleSignInConfigured()) {
            Toast.makeText(this, R.string.account_google_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        setHomeGoogleLoginLoading(true)
        val client = FirebaseAuthSupport.googleSignInClient(this)
        client.signOut().addOnCompleteListener {
            marvinGoogleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun handleMarvinGoogleSignInResult(data: Intent?) {
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        }.getOrElse {
            setHomeGoogleLoginLoading(false)
            Toast.makeText(
                this,
                FirebaseAuthSupport.describeGoogleSignInError(it),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            setHomeGoogleLoginLoading(false)
            Toast.makeText(this, R.string.account_google_missing_token, Toast.LENGTH_LONG).show()
            return
        }

        activityScope.launch {
            val result = runCatching {
                FirebaseAuthSupport.signInWithGoogleIdToken(this@MainActivity, idToken)
            }
            setHomeGoogleLoginLoading(false)
            result.onSuccess {
                Toast.makeText(
                    this@MainActivity,
                    R.string.account_google_signed_in,
                    Toast.LENGTH_SHORT
                ).show()
                refreshDashboard()
            }.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    FirebaseAuthSupport.describeGoogleSignInError(it),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setHomeGoogleLoginLoading(loading: Boolean) {
        homeGoogleLoginButton.isEnabled = !loading && FirebaseAuthSupport.isGoogleSignInConfigured()
        homeGoogleLoginButton.text = getString(
            if (loading) R.string.account_google_signing_in else R.string.account_google_continue
        )
    }

    private fun bindRow(rowId: Int, title: String, ok: Boolean, target: Class<*>) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowStatus).text = getString(
            if (ok) R.string.status_ok else R.string.status_action_needed
        )
        row.findViewById<View>(R.id.rowDot).setBackgroundResource(
            if (ok) R.drawable.bg_status_dot_ok else R.drawable.bg_status_dot_attention
        )
        row.setOnClickListener { startActivity(Intent(this, target)) }
    }

    private fun bindMemoryRow() {
        val ok = !MemoryOnboardingManager(MemoryRepository(applicationContext)).needsOnboarding()
        val row = findViewById<View>(R.id.rowMemory)
        row.findViewById<TextView>(R.id.rowTitle).text = getString(R.string.row_memory_title)
        row.findViewById<TextView>(R.id.rowStatus).text = getString(
            if (ok) R.string.status_ok else R.string.status_action_needed
        )
        row.findViewById<View>(R.id.rowDot).setBackgroundResource(
            if (ok) R.drawable.bg_status_dot_ok else R.drawable.bg_status_dot_attention
        )
        row.setOnClickListener {
            startActivity(
                Intent(this, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_MEMORY_ONLY, true)
            )
        }
    }

    private fun maybeHandleSpotifyRedirect(intent: Intent?, returnToOnboarding: Boolean = false) {
        val redirectUri = intent?.data ?: return
        if (!spotifyService.isSpotifyRedirect(redirectUri)) return

        clearSpotifyRedirectIntent(intent)
        activityScope.launch {
            val result = spotifyService.handleRedirect(redirectUri)
            if (result.handled) {
                refreshDashboard()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    result.message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
                if (returnToOnboarding) {
                    returnToOnboardingAfterSpotify()
                }
            }
        }
    }

    private fun returnToOnboardingAfterSpotify() {
        val spotifyConnected = spotifyService.connectionStatus().isConnected
        val nextStep = if (spotifyConnected) {
            OnboardingActivity.STEP_DONE
        } else {
            OnboardingActivity.STEP_SPOTIFY
        }
        startActivity(
            Intent(this, OnboardingActivity::class.java)
                .putExtra(OnboardingActivity.EXTRA_INITIAL_STEP, nextStep)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun clearSpotifyRedirectIntent(intent: Intent) {
        setIntent(Intent(intent).apply { data = null })
    }

    private fun isSignedIn(): Boolean = AuthCoordinator.currentUser(this) != null

    private fun loginIntent(): Intent {
        return Intent(this, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_INITIAL_STEP, OnboardingActivity.STEP_AUTH_WELCOME)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

}
