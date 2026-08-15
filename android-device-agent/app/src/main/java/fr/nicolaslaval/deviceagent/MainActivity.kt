package fr.nicolaslaval.deviceagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ecran unique, direction "console de liaison" simplifiee : l'orbe du haut
 * fusionne icone/etat/client/action de connexion, les deux lignes du bas
 * fusionnent chacune titre/statut/action. Le polling reseau vit dans
 * PollingService, la capture dans ScreenCaptureService — cette activite ne
 * fait que piloter l'etat et demarrer/arreter ces services.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var keyManager: DeviceKeyManager
    private val defaultServerUrl = "https://user-nic01asfr-device-agent.user.lab.sspcloud.fr"

    private lateinit var heroContainer: LinearLayout
    private lateinit var heroOrbBackground: android.view.View
    private lateinit var heroOrbIcon: ImageView
    private lateinit var heroStatusText: TextView
    private lateinit var heroSubText: TextView

    private lateinit var enrollmentCodeInput: EditText
    private lateinit var enrollButton: TextView

    private lateinit var accessibilityRow: LinearLayout
    private lateinit var accessibilityIcon: ImageView
    private lateinit var accessibilitySubText: TextView

    private lateinit var captureRow: LinearLayout
    private lateinit var captureIcon: ImageView
    private lateinit var screenCaptureStatusText: TextView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ScreenCaptureService.start(this, result.resultCode, result.data!!)
                // Laisse un instant au service pour s'initialiser avant de lire son etat.
                heroContainer.postDelayed({ refreshCaptureUi() }, 500)
            } else {
                screenCaptureStatusText.text = "refusee — appuie pour reessayer"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("device_agent_prefs", Context.MODE_PRIVATE)
        keyManager = DeviceKeyManager(prefs)
        keyManager.ensureKeyPair()
        if (prefs.getString("server_url", null) == null) {
            prefs.edit().putString("server_url", defaultServerUrl).apply()
        }

        heroContainer = findViewById(R.id.heroContainer)
        heroOrbBackground = findViewById(R.id.heroOrbBackground)
        heroOrbIcon = findViewById(R.id.heroOrbIcon)
        heroStatusText = findViewById(R.id.heroStatusText)
        heroSubText = findViewById(R.id.heroSubText)

        enrollmentCodeInput = findViewById(R.id.enrollmentCodeInput)
        enrollButton = findViewById(R.id.enrollButton)

        accessibilityRow = findViewById(R.id.accessibilityRow)
        accessibilityIcon = findViewById(R.id.accessibilityIcon)
        accessibilitySubText = findViewById(R.id.accessibilitySubText)

        captureRow = findViewById(R.id.captureRow)
        captureIcon = findViewById(R.id.captureIcon)
        screenCaptureStatusText = findViewById(R.id.screenCaptureStatusText)

        requestNotificationPermissionIfNeeded()
        refreshHeroUi()
        refreshCaptureUi()

        heroContainer.setOnClickListener {
            if (!prefs.getBoolean("enrolled", false)) {
                heroSubText.text = "enrole d'abord cet appareil ci-dessous"
                return@setOnClickListener
            }
            if (prefs.getString("session_token", null) == null) onConnectClicked() else onDisconnectClicked()
        }
        enrollButton.setOnClickListener { onEnrollClicked() }
        accessibilityRow.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        captureRow.setOnClickListener {
            if (ScreenCaptureService.instance != null) {
                ScreenCaptureService.stop(this)
                refreshCaptureUi()
            } else {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityUi()
        refreshCaptureUi()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- Etat visuel -----------------------------------------------------

    private fun refreshHeroUi() {
        val connected = prefs.getString("session_token", null) != null
        heroOrbBackground.background = ContextCompat.getDrawable(
            this, if (connected) R.drawable.bg_orb_connected else R.drawable.bg_orb_disconnected
        )
        heroOrbIcon.setColorFilter(
            ContextCompat.getColor(this, if (connected) android.R.color.white else R.color.text_secondary)
        )
        if (connected) {
            heroStatusText.text = "Connecte a Claude"
            heroStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent_blue_light))
            heroSubText.text = "appuie pour deconnecter"
        } else {
            val enrolled = prefs.getBoolean("enrolled", false)
            heroStatusText.text = if (enrolled) "Non connecte" else "Non enrole"
            heroStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            heroSubText.text = if (enrolled) "appuie pour connecter" else "enrole cet appareil ci-dessous"
        }
    }

    private fun refreshAccessibilityUi() {
        val enabled = isControlServiceEnabled()
        tintAccessibilityIcon(enabled)
        accessibilitySubText.text = if (enabled) "actif" else "desactive — appuie pour l'activer"
    }

    private fun tintAccessibilityIcon(enabled: Boolean) {
        val bg = accessibilityIcon.parent as FrameLayout
        bg.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle)
        bg.background?.setTint(
            ContextCompat.getColor(this, if (enabled) R.color.status_green else R.color.status_gray)
        )
    }

    private fun refreshCaptureUi() {
        val armed = ScreenCaptureService.instance != null
        tintCaptureIcon(armed)
        screenCaptureStatusText.text = if (armed) "armee — appuie pour desarmer" else "non armee — appuie pour armer"
    }

    private fun tintCaptureIcon(armed: Boolean) {
        val bg = captureIcon.parent as FrameLayout
        bg.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_circle)
        bg.background?.setTint(
            ContextCompat.getColor(this, if (armed) R.color.status_green else R.color.status_gray)
        )
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

    // --- Actions -----------------------------------------------------------

    private fun relayClient(): RelayClient {
        val url = prefs.getString("server_url", defaultServerUrl) ?: defaultServerUrl
        return RelayClient(url)
    }

    private fun onEnrollClicked() {
        val code = enrollmentCodeInput.text.toString().trim()
        if (code.isEmpty()) {
            heroSubText.text = "code d'appairage manquant"
            return
        }
        lifecycleScope.launch {
            enrollButton.text = "Enrolement..."
            try {
                withContext(Dispatchers.IO) {
                    relayClient().enroll(code, keyManager.deviceId, keyManager.publicKeyDerBase64())
                }
                prefs.edit().putBoolean("enrolled", true).apply()
                enrollButton.text = "Valider le code"
                refreshHeroUi()
            } catch (e: Exception) {
                enrollButton.text = "Valider le code"
                heroSubText.text = "echec enrolement"
            }
        }
    }

    private fun onConnectClicked() {
        lifecycleScope.launch {
            heroSubText.text = "connexion..."
            try {
                val token = withContext(Dispatchers.IO) {
                    val client = relayClient()
                    val nonce = client.requestChallenge(keyManager.deviceId)
                    val signature = keyManager.signNonce(nonce)
                    client.openSession(keyManager.deviceId, nonce, signature)
                }
                prefs.edit().putString("session_token", token).apply()
                refreshHeroUi()
                PollingService.start(this@MainActivity)
            } catch (e: Exception) {
                heroSubText.text = "echec connexion"
            }
        }
    }

    private fun onDisconnectClicked() {
        val token = prefs.getString("session_token", null) ?: return
        prefs.edit().remove("session_token").apply()
        PollingService.stop(this)
        refreshHeroUi()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { relayClient().disconnect(token) }
            } catch (_: Exception) {
            }
        }
    }
}
