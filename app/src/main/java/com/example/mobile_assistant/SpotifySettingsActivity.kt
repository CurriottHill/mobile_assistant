package com.example.mobile_assistant

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

class SpotifySettingsActivity : AppCompatActivity() {

    private val spotifyService by lazy { SpotifyService(applicationContext) }
    private lateinit var status: TextView
    private lateinit var action: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_spotify_settings)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_spotify_settings)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.textStatus)
        action = findViewById(R.id.btnAction)
        action.setOnClickListener {
            SetupChecks.launchSpotifyLogin(this, spotifyService)
        }
    }

    override fun onResume() {
        super.onResume()
        val connection = spotifyService.connectionStatus()
        status.text = connection.statusText
        action.text = getString(
            if (connection.isConnected) R.string.spotify_reconnect else R.string.spotify_connect
        )
        action.isEnabled = connection.isConfigured
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
