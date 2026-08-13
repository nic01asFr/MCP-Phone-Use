package fr.nicolaslaval.deviceagent

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ecran unique v0.1 : enrolement (une fois) puis Connecter/Deconnecter.
 *
 * Ne fait PAS encore de controle reel (AccessibilityService/MediaProjection) —
 * c'est l'etape suivante du backlog (docs/architecture.md). Cette version
 * valide uniquement la chaine cle Keystore + challenge-response de bout en
 * bout sur un vrai appareil.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var keyManager: DeviceKeyManager
    private var sessionToken: String? = null

    private lateinit var statusText: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var enrollmentCodeInput: EditText
    private lateinit var enrollButton: Button
    private lateinit var connectButton: Button

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

        serverUrlInput.setText(
            prefs.getString("server_url", "https://user-nic01asfr-device-agent.user.lab.sspcloud.fr")
        )

        val alreadyEnrolled = prefs.getBoolean("enrolled", false)
        connectButton.isEnabled = alreadyEnrolled
        updateStatus(if (alreadyEnrolled) "Enrole — pret a connecter" else "Non enrole")

        enrollButton.setOnClickListener { onEnrollClicked() }
        connectButton.setOnClickListener {
            if (sessionToken == null) onConnectClicked() else onDisconnectClicked()
        }
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
                sessionToken = token
                connectButton.text = "Deconnecter"
                updateStatus("Connecte (${keyManager.deviceId})")
            } catch (e: Exception) {
                updateStatus("Echec connexion: ${e.message}")
            }
        }
    }

    private fun onDisconnectClicked() {
        val token = sessionToken ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { relayClient().disconnect(token) }
            } catch (_: Exception) {
                // best-effort : on deconnecte localement meme si l'appel echoue
            }
            sessionToken = null
            connectButton.text = "Connecter"
            updateStatus("Deconnecte")
        }
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }
}
