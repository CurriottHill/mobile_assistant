package com.example.mobile_assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Thin launcher Activity that handles the ASSIST / VOICE_ASSIST intent.
 *
 * All it does is:
 *  1. Ensure RECORD_AUDIO permission is granted (the overlay can't request it).
 *  2. Tell the AccessibilityService to show the overlay.
 *  3. Immediately finish() so it is never the foreground window.
 *
 * The actual UI lives in [AssistantOverlayController], hosted by
 * [AssistantAccessibilityService] via TYPE_ACCESSIBILITY_OVERLAY so the
 * underlying app stays the active window.
 */
class AssistantActivity : AppCompatActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val micGranted = grants[Manifest.permission.RECORD_AUDIO]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

            if (!micGranted) {
                Toast.makeText(this, getString(R.string.assist_mic_permission_required), Toast.LENGTH_SHORT).show()
                finish()
                return@registerForActivityResult
            }

            val contactsGranted = grants[Manifest.permission.READ_CONTACTS]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
            if (!contactsGranted) {
                Toast.makeText(this, getString(R.string.assist_contacts_permission_recommended), Toast.LENGTH_SHORT).show()
            }

            val callLogGranted = grants[Manifest.permission.READ_CALL_LOG]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
            if (!callLogGranted) {
                Toast.makeText(this, getString(R.string.assist_call_log_permission_recommended), Toast.LENGTH_SHORT).show()
            }

            val callGranted = grants[Manifest.permission.CALL_PHONE]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            if (!callGranted) {
                Toast.makeText(this, getString(R.string.assist_call_permission_recommended), Toast.LENGTH_SHORT).show()
            }

            val smsGranted = grants[Manifest.permission.SEND_SMS]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
            if (!smsGranted) {
                Toast.makeText(this, getString(R.string.assist_sms_permission_recommended), Toast.LENGTH_SHORT).show()
            }

            showOverlayAndFinish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missingPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.RECORD_AUDIO
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.READ_CONTACTS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.READ_CALL_LOG
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.CALL_PHONE
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.SEND_SMS
        }

        if (missingPermissions.isEmpty()) {
            showOverlayAndFinish()
        } else {
            requestPermissions.launch(missingPermissions.toTypedArray())
        }
    }

    private fun showOverlayAndFinish() {
        val service = AssistantAccessibilityService.instance
        if (service != null) {
            service.showOverlay()
        } else {
            Toast.makeText(
                this,
                getString(R.string.assist_accessibility_not_enabled),
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }
}
