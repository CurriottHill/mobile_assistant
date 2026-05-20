package com.example.mobile_assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val spotifyService by lazy { SpotifyService(applicationContext) }
    private val googleAccountService by lazy { GoogleAccountService(applicationContext) }
    private val prefs by lazy { getSharedPreferences("aura_prefs", MODE_PRIVATE) }

    private lateinit var textReadyStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isSpotifyRedirect = spotifyService.isSpotifyRedirect(intent?.data)
        val onboardingDone = prefs.getBoolean("onboarding_complete", false)
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
        bindStaticClickListeners()
        refreshDashboard()
        maybeHandleSpotifyRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleSpotifyRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun bindStaticClickListeners() {
        findViewById<View>(R.id.btnOpenAssistant).setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
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

        val attention = listOf(accessibilityOk, assistantOk, permissionsOk, locationOk).count { !it }
        textReadyStatus.text = if (attention == 0) {
            getString(R.string.dashboard_summary_all_set)
        } else {
            getString(R.string.dashboard_summary_attention, attention)
        }
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

    private fun maybeHandleSpotifyRedirect(intent: Intent?) {
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
            }
        }
    }

    private fun clearSpotifyRedirectIntent(intent: Intent) {
        setIntent(Intent(intent).apply { data = null })
    }

}
