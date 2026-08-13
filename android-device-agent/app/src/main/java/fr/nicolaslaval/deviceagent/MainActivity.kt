package fr.nicolaslaval.deviceagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ecran unique. Le polling reseau vit dans PollingService (premier plan,
 * survit a la mise en arriere-plan de cette activite) — MainActivity ne fait
 * que piloter enrolement/connexion et demarrer/arreter ce service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var keyManager: DeviceKeyManager

    private lateinit var statusText: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var enrollmentCodeInput: EditText
    private lateinit var enrollButton: Button
    private lateinit var connectButton: Button
    private lateinit var accessibilityStatusText: TextView
    private lateinit var openAccessibilitySettingsButton: Button

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore le resultat : best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("device_agent_prefs", Context.MODE_PRIVATE)
        keyManager = DeviceKeyManager(prefs)
        keyManager.ensureKeyPair()

        statusText = findViewById(R.id.statusText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        enrollmentCodeInput = findViewById(R.id.enrollmentCodeInput)
        enrollButton = findViewById(R.id.enrollButton)
        connectButton = findViewById(R.id.connectButton)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        openAccessibilitySettingsButton = findViewById(R.id.openAccessibilitySettingsButton)

        serverUrlInput.setText(
            prefs.getString("server_url", "https://user-nic01asfr-device-agent.user.lab.sspcloud.fr")
        )

        val alreadyEnrolled = prefs.getBoolean("enrolled", false)
        val existingToken = prefs.getString("session_token", null)
        connectButton.isEnabled = alreadyEnrolled
        if (existingToken != null) {
            connectButton.text = "Deconnecter"
            updateStatus("Connecte (${keyManager.deviceId})")
        } else {
            updateStatus(if (alreadyEnrolled) "Enrole — pret a connecter" else "Non enrole")
        }

        requestNotificationPermissionIfNeeded()

        enrollButton.setOnClickListener { onEnrollClicked() }
        connectButton.setOnClickListener {
            if (prefs.getString("session_token", null) == null) onConnectClicked() else onDisconnectClicked()
        }
        openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshAccessibilityStatus() {
        val enabled = isControlServiceEnabled()
        accessibilityStatusText.text =
            if (enabled) "Controle de l'ecran : actif" else "Controle de l'ecran : desactive"
    }

    private fun isControlServiceEnabled(): Boolean {
        val expected = "$packageName/${ControlService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun relayClient(): RelayClient {
        val url = serverUrlInput.text.toString().trim()
        prefs.edit().putString("server_url", url).apply()
        return RelayClient(url)
    }

    private fun onEnrollClicked() {
        val code = enrollmentCodeInput.text.toString().trim()
        if (code.isEmpty()) {
            updateStatus("Code d'enrolement manquant")
            return
        }
        lifecycleScope.launch {
            updateStatus("Enrolement en cours...")
            try {
                withContext(Dispatchers.IO) {
                    relayClient().enroll(code, keyManager.deviceId, keyManager.publicKeyDerBase64())
                }
                prefs.edit().putBoolean("enrolled", true).apply()
                connectButton.isEnabled = true
                updateStatus("Enrole (${keyManager.deviceId}) — pret a connecter")
            } catch (e: Exception) {
                updateStatus("Echec enrolement: ${e.message}")
            }
        }
    }

    private fun onConnectClicked() {
        lifecycleScope.launch {
            updateStatus("Connexion en cours...")
            try {
                val token = withContext(Dispatchers.IO) {
                    val client = relayClient()
                    val nonce = client.requestChallenge(keyManager.deviceId)
                    val signature = keyManager.signNonce(nonce)
                    client.openSession(keyManager.deviceId, nonce, signature)
                }
                prefs.edit().putString("session_token", token).apply()
                connectButton.text = "Deconnecter"
                updateStatus("Connecte (${keyManager.deviceId})")
                PollingService.start(this@MainActivity)
            } catch (e: Exception) {
                updateStatus("Echec connexion: ${e.message}")
            }
        }
    }

    private fun onDisconnectClicked() {
        val token = prefs.getString("session_token", null) ?: return
        prefs.edit().remove("session_token").apply()
        PollingService.stop(this)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { relayClient().disconnect(token) }
            } catch (_: Exception) {
                // best-effort : deconnexion locale actee meme si l'appel echoue
            }
            connectButton.text = "Connecter"
            updateStatus("Deconnecte")
        }
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }
}
