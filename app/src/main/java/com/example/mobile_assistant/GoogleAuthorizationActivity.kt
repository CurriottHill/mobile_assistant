package com.example.mobile_assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GoogleAuthorizationActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val googleAccountService by lazy { GoogleAccountService(applicationContext) }
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private var launched = false

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            toastAndFinish("Google account connection was cancelled.")
            return@registerForActivityResult
        }

        val authorizationResult = runCatching {
            authorizationClient.getAuthorizationResultFromIntent(result.data)
        }.getOrElse {
            toastAndFinish(it.message ?: "Google account connection failed.")
            return@registerForActivityResult
        }

        activityScope.launch {
            val authResult = googleAccountService.saveAuthorizationResult(authorizationResult)
            Toast.makeText(this@GoogleAuthorizationActivity, authResult.message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            launched = savedInstanceState.getBoolean(KEY_LAUNCHED, false)
        }
        if (!launched) {
            launched = true
            startAuthorization()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LAUNCHED, launched)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun startAuthorization() {
        authorizationClient.authorize(googleAccountService.authorizationRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        toastAndFinish("Google account connection needs consent, but no consent screen was returned.")
                        return@addOnSuccessListener
                    }
                    authorizationLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    activityScope.launch {
                        val authResult = googleAccountService.saveAuthorizationResult(result)
                        Toast.makeText(
                            this@GoogleAuthorizationActivity,
                            authResult.message,
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }
            .addOnFailureListener {
                toastAndFinish(it.message ?: "Google account connection failed.")
            }
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val KEY_LAUNCHED = "launched"
    }
}
