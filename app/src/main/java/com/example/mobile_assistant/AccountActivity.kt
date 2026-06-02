package com.example.mobile_assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AccountActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backendClient by lazy { MobileBackendClient(applicationContext) }

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var statusText: TextView
    private lateinit var planText: TextView
    private lateinit var usageText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var signOutButton: Button
    private lateinit var billingButton: Button
    private lateinit var googleButton: Button

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGoogleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        emailInput = findViewById(R.id.inputAccountEmail)
        passwordInput = findViewById(R.id.inputAccountPassword)
        statusText = findViewById(R.id.textAccountStatus)
        planText = findViewById(R.id.textAccountPlan)
        usageText = findViewById(R.id.textAccountUsage)
        progress = findViewById(R.id.progressAccount)
        signOutButton = findViewById(R.id.btnAccountSignOut)
        billingButton = findViewById(R.id.btnAccountBilling)
        googleButton = findViewById(R.id.btnAccountGoogle)

        findViewById<Button>(R.id.btnAccountCreate).setOnClickListener { submit(create = true) }
        findViewById<Button>(R.id.btnAccountSignIn).setOnClickListener { submit(create = false) }
        googleButton.setOnClickListener { startGoogleSignIn() }
        signOutButton.setOnClickListener { signOut() }
        billingButton.setOnClickListener { openBilling() }

        if (!FirebaseAuthSupport.ensureInitialized(this)) {
            statusText.text = getString(R.string.account_firebase_not_configured)
            googleButton.isEnabled = false
            googleButton.text = getString(R.string.account_google_unavailable)
            setSignedInUi(false)
            return
        }

        FirebaseAuthSupport.auth(this).currentUser?.email?.let { emailInput.setText(it) }
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun submit(create: Boolean) {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString()?.trim().orEmpty()
        if (email.isBlank() || password.length < 6) {
            Toast.makeText(this, getString(R.string.account_email_password_required), Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        activityScope.launch {
            val result = runCatching {
                val auth = FirebaseAuthSupport.auth(this@AccountActivity)
                if (create) {
                    auth.createUserWithEmailAndPassword(email, password).awaitTask()
                } else {
                    auth.signInWithEmailAndPassword(email, password).awaitTask()
                }
            }
            result.exceptionOrNull()?.let {
                statusText.text = it.message ?: getString(R.string.account_sign_in_failed)
                setLoading(false)
                return@launch
            }
            passwordInput.text?.clear()
            refresh()
        }
    }

    private fun startGoogleSignIn() {
        if (!FirebaseAuthSupport.isGoogleSignInConfigured()) {
            Toast.makeText(this, R.string.account_google_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)
        val client = FirebaseAuthSupport.googleSignInClient(this)
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun handleGoogleSignInResult(data: Intent?) {
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        }.getOrElse {
            statusText.text = FirebaseAuthSupport.describeGoogleSignInError(it)
            setLoading(false)
            return
        }
        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            statusText.text = getString(R.string.account_google_missing_token)
            setLoading(false)
            return
        }

        activityScope.launch {
            val result = runCatching {
                FirebaseAuthSupport.signInWithGoogleIdToken(this@AccountActivity, idToken)
            }
            result.onSuccess {
                passwordInput.text?.clear()
                refresh()
            }.onFailure {
                statusText.text = FirebaseAuthSupport.describeGoogleSignInError(it)
                setLoading(false)
            }
        }
    }

    private fun refresh() {
        val user = FirebaseAuthSupport.auth(this).currentUser
        setSignedInUi(user != null)
        if (user == null) {
            statusText.text = getString(R.string.account_signed_out)
            planText.text = getString(R.string.account_plan_placeholder)
            usageText.text = getString(R.string.account_usage_placeholder)
            return
        }

        statusText.text = getString(R.string.account_signed_in_as, user.email ?: "")
        setLoading(true)
        activityScope.launch {
            val summary = runCatching { backendClient.accountSummary() }
            summary.onSuccess {
                planText.text = getString(
                    R.string.account_plan_value,
                    it.plan.replaceFirstChar { ch -> ch.titlecase() }
                )
                usageText.text = getString(
                    R.string.account_usage_value,
                    it.remainingCreditsThisMonth,
                    it.monthlyCreditLimit,
                    it.estimatedModelCostUsd
                )
            }.onFailure {
                statusText.text = it.message ?: getString(R.string.account_load_failed)
            }
            setLoading(false)
        }
    }

    private fun signOut() {
        FirebaseAuthSupport.auth(this).signOut()
        refresh()
    }

    private fun openBilling() {
        val billingUrl = BuildConfig.MARVIN_BILLING_URL.trim()
        if (billingUrl.isBlank()) {
            Toast.makeText(this, R.string.account_billing_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(billingUrl))
        startActivity(intent)
    }

    private fun setSignedInUi(signedIn: Boolean) {
        googleButton.visibility = if (signedIn) View.GONE else View.VISIBLE
        googleButton.isEnabled = !signedIn && FirebaseAuthSupport.isGoogleSignInConfigured()
        googleButton.text = getString(
            if (FirebaseAuthSupport.isGoogleSignInConfigured()) {
                R.string.account_google_continue
            } else {
                R.string.account_google_unavailable
            }
        )
        signOutButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        billingButton.visibility = if (signedIn) View.VISIBLE else View.GONE
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
