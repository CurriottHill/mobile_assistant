package com.example.mobile_assistant

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

class AccessibilitySettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_accessibility_settings)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_accessibility_settings)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.textStatus)
        findViewById<Button>(R.id.btnAction).setOnClickListener {
            SetupChecks.openAccessibilitySettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        val ok = SetupChecks.isAccessibilityEnabled(this)
        status.text = getString(
            if (ok) R.string.accessibility_status_ready else R.string.accessibility_status_missing
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
