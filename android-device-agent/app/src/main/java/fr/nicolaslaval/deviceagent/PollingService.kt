package fr.nicolaslaval.deviceagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Service de premier plan : fait tourner la boucle de polling tant qu'une
 * session est active, meme si MainActivity n'est plus au premier plan.
 * La notification persistante est obligatoire (Android) et volontaire ici :
 * c'est le signal visible que device-agent peut recevoir des commandes.
 *
 * Lit l'etat (server_url, session_token) depuis les memes SharedPreferences
 * que MainActivity — pas de duplication d'etat, juste deux lecteurs.
 */
class PollingService : Service() {

    companion object {
        private const val CHANNEL_ID = "device_agent_polling"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PollingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PollingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPollingLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startPollingLoop() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            val prefs = getSharedPreferences("device_agent_prefs", Context.MODE_PRIVATE)
            val keyManager = DeviceKeyManager(prefs)
            val serverUrl = prefs.getString("server_url", null)
            if (serverUrl.isNullOrBlank()) {
                stopSelf()
                return@launch
            }
            val client = RelayClient(serverUrl)
            val deviceId = keyManager.deviceId

            while (isActive) {
                val sessionToken = prefs.getString("session_token", null)
                if (sessionToken == null) {
                    stopSelf()
                    return@launch
                }
                try {
                    val response = client.pollCommands(deviceId)
                    val commands = response.optJSONArray("commands")
                    if (commands != null) {
                        for (i in 0 until commands.length()) {
                            val cmd = commands.getJSONObject(i)
                            val commandId = cmd.getString("id")
                            val type = cmd.getString("type")
                            val params = cmd.optJSONObject("params") ?: JSONObject()
                            val service = ControlService.instance
                            val result = if (service != null) {
                                service.execute(type, params)
                            } else {
                                JSONObject().put("ok", false).put("error", "AccessibilityService non actif")
                            }
                            client.submitResult(commandId, result)
                        }
                    }
                } catch (_: Exception) {
                    // best-effort : erreur reseau ponctuelle, la boucle continue
                }
                delay(1500)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "device-agent actif", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("device-agent")
            .setContentText("Connecte — pret a recevoir des commandes")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }
}
