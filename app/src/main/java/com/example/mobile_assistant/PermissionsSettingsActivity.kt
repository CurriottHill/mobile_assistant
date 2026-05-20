package com.example.mobile_assistant

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

class PermissionsSettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_permissions_settings)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_permissions_settings)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.textStatus)
        findViewById<Button>(R.id.btnAction).setOnClickListener {
            SetupChecks.requestMissingPermissions(this, requestPermissions)
        }
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener {
            SetupChecks.openAppDetailsSettings(this)
        }
        findViewById<Button>(R.id.btnNotificationAccess).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val missing = SetupChecks.missingRuntimePermissionLabels(this)
        val permissionLine = if (missing.isEmpty()) {
            getString(R.string.permissions_status_ready)
        } else {
            getString(R.string.permissions_status_missing, missing.joinToString())
        }
        val notificationLine = if (isNotificationAccessEnabled()) {
            getString(R.string.permissions_notification_access_on)
        } else {
            getString(R.string.permissions_notification_access_off)
        }
        status.text = "$permissionLine\n$notificationLine"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
