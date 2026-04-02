package com.example.mobile_assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
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

    private lateinit var btnSpotifyLogin: Button
    private lateinit var textSpotifyStatus: TextView
    private lateinit var layoutApiKey: LinearLayout
    private lateinit var editApiKey: EditText
    private lateinit var textInstructions: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        showConsentDialogIfNeeded()
        bindViews()
        bindClickListeners()
        refreshApiKeySection()
        refreshSpotifyStatus()
        maybeHandleSpotifyRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleSpotifyRedirect(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun bindViews() {
        btnSpotifyLogin = findViewById(R.id.btnSpotifyLogin)
        textSpotifyStatus = findViewById(R.id.textSpotifyStatus)
        layoutApiKey = findViewById(R.id.layoutApiKey)
        editApiKey = findViewById(R.id.editApiKey)
        textInstructions = findViewById(R.id.textInstructions)
    }

    private fun refreshApiKeySection() {
        if (ApiKeyStore.hasAnthropicKey(this)) {
            layoutApiKey.visibility = View.GONE
            textInstructions.visibility = View.VISIBLE
        } else {
            layoutApiKey.visibility = View.VISIBLE
            textInstructions.visibility = View.GONE
        }
    }

    private fun bindClickListeners() {
        findViewById<Button>(R.id.btnSaveApiKey).setOnClickListener {
            val key = editApiKey.text.toString().trim()
            if (key.isBlank() || !key.startsWith("sk-ant-")) {
                showToast(getString(R.string.api_key_invalid))
                return@setOnClickListener
            }
            ApiKeyStore.setAnthropicKey(this, key)
            editApiKey.setText("")
            refreshApiKeySection()
            showToast(getString(R.string.api_key_saved))
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(packageName, AssistantAccessibilityService::class.java.name)
            accessibilityIntent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            accessibilityIntent.putExtra("page_fragment_args_key", componentName.flattenToString())
            startActivity(accessibilityIntent)
        }

        findViewById<TextView>(R.id.btnTerms).setOnClickListener {
            startActivity(Intent(this, TermsActivity::class.java))
        }

        findViewById<TextView>(R.id.btnPrivacy).setOnClickListener {
            startActivity(Intent(this, PrivacyPolicyActivity::class.java))
        }

        btnSpotifyLogin.setOnClickListener {
            val launchResult = spotifyService.createLoginIntent()
            if (!launchResult.ok || launchResult.intent == null) {
                refreshSpotifyStatus()
                showToast(launchResult.message)
                return@setOnClickListener
            }

            if (launchResult.intent.resolveActivity(packageManager) == null) {
                showToast(getString(R.string.spotify_no_browser_found))
                return@setOnClickListener
            }

            startActivity(launchResult.intent)
        }
    }

    private fun refreshSpotifyStatus() {
        val status = spotifyService.connectionStatus()
        textSpotifyStatus.text = status.statusText
        btnSpotifyLogin.text = if (status.isConnected) {
            getString(R.string.spotify_reconnect)
        } else {
            getString(R.string.spotify_connect)
        }
        btnSpotifyLogin.isEnabled = status.isConfigured
    }

    private fun maybeHandleSpotifyRedirect(intent: Intent?) {
        val redirectUri = intent?.data ?: return
        if (!spotifyService.isSpotifyRedirect(redirectUri)) return

        clearSpotifyRedirectIntent(intent)
        activityScope.launch {
            val result = spotifyService.handleRedirect(redirectUri)
            if (result.handled) {
                refreshSpotifyStatus()
                showToast(result.message)
            }
        }
    }

    private fun clearSpotifyRedirectIntent(intent: Intent) {
        setIntent(Intent(intent).apply { data = null })
    }

    private fun showConsentDialogIfNeeded() {
        val prefs = getSharedPreferences("aura_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("consent_accepted", false)) return

        val raw = "By using Aura you agree to our Terms & Conditions and Privacy Policy."
        val spannable = SpannableString(raw)

        val termsStart = raw.indexOf("Terms & Conditions")
        val termsEnd = termsStart + "Terms & Conditions".length
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(this@MainActivity, TermsActivity::class.java))
            }
        }, termsStart, termsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val privacyStart = raw.indexOf("Privacy Policy")
        val privacyEnd = privacyStart + "Privacy Policy".length
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(this@MainActivity, PrivacyPolicyActivity::class.java))
            }
        }, privacyStart, privacyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val messageView = TextView(this).apply {
            text = spannable
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(64, 32, 64, 16)
            textSize = 15f
            setLineSpacing(0f, 1.4f)
        }

        AlertDialog.Builder(this)
            .setTitle("Terms & Privacy")
            .setView(messageView)
            .setPositiveButton("Accept") { _, _ ->
                prefs.edit().putBoolean("consent_accepted", true).apply()
            }
            .setNegativeButton("Decline") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
