package fr.nic01asfr.deviceagent

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Ecran unique : configuration du relais, enrolement (une fois), puis
 * connexion/deconnexion (challenge-response, pas de secret statique).
 *
 * Portee de cette version : le pairing et la preuve de connexion aupres
 * du relais. AccessibilityService / MediaProjection (controle reel de
 * l'ecran/UI) arrivent dans une iteration suivante -- voir docs/architecture.md.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var serverUrlField: EditText
    private lateinit var enrollmentCodeField: EditText

    private var sessionToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("device_agent_prefs", Context.MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        serverUrlField = findViewById(R.id.serverUrlField)
        enrollmentCodeField = findViewById(R.id.enrollmentCodeField)

        serverUrlField.setText(prefs.getString("server_url", ""))

        findViewById<Button>(R.id.enrollButton).setOnClickListener { onEnrollClicked() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { onDisconnectClicked() }

        updateStatus("Non connecte")
    }

    private fun deviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "android-" + UUID.randomUUID().toString().take(12)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun serverUrl(): String = serverUrlField.text.toString().trim().trimEnd('/')

    private fun updateStatus(text: String) {
        statusText.text = text
    }

    private fun onEnrollClicked() {
        val url = serverUrl()
        val code = enrollmentCodeField.text.toString().trim()
        if (url.isEmpty() || code.isEmpty()) {
            toast("URL du relais et code d'enrolement requis")
            return
        }
        prefs.edit().putString("server_url", url).apply()

        lifecycleScope.launch {
            try {
                val pubKey = withContext(Dispatchers.Default) { KeyManager.publicKeyDerBase64() }
                withContext(Dispatchers.IO) {
                    ApiClient.enroll(url, code, deviceId(), pubKey)
                }
                updateStatus("Enrole (device_id=${deviceId()}). Tu peux te connecter.")
                toast("Enrolement reussi")
            } catch (e: Exception) {
                Log.e("device-agent", "Echec enrolement", e)
                updateStatus("Echec de l'enrolement")
                toast("Erreur: ${e.message}")
            }
        }
    }

    private fun onConnectClicked() {
        val url = serverUrl()
        if (url.isEmpty()) {
            toast("URL du relais requise")
            return
        }
        lifecycleScope.launch {
            try {
                val id = deviceId()
                val nonce = withContext(Dispatchers.IO) { ApiClient.requestChallenge(url, id) }
                val signature = withContext(Dispatchers.Default) { KeyManager.signNonce(nonce) }
                val token = withContext(Dispatchers.IO) { ApiClient.openSession(url, id, nonce, signature) }
                sessionToken = token
                updateStatus("Connecte (session active)")
                toast("Connexion etablie")
            } catch (e: Exception) {
                Log.e("device-agent", "Echec connexion", e)
                updateStatus("Echec de connexion")
                toast("Erreur: ${e.message}")
            }
        }
    }

    private fun onDisconnectClicked() {
        val token = sessionToken ?: run {
            updateStatus("Non connecte")
            return
        }
        val url = serverUrl()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.disconnect(url, token) }
            } catch (e: Exception) {
                Log.w("device-agent", "Erreur deconnexion (on desarme quand meme localement)", e)
            }
            sessionToken = null
            updateStatus("Non connecte")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
