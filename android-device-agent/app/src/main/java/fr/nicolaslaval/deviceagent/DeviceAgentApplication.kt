package fr.nicolaslaval.deviceagent

import android.app.Application
import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Capte tout crash non gere (n'importe quel composant : Activity, Service...)
 * et envoie la trace au relais AVANT que le processus ne meure — appel HTTP
 * bloquant, pas de coroutine (le process peut disparaitre avant qu'elle finisse).
 *
 * Objectif : diagnostiquer sans jamais avoir besoin d'ADB/ordinateur, cohérent
 * avec le principe du projet (tout depuis le mobile). Voir docs/architecture.md.
 */
class DeviceAgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                reportCrashBlocking(throwable)
            } catch (_: Exception) {
                // best-effort : ne jamais empecher le crash normal de se produire
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun reportCrashBlocking(throwable: Throwable) {
        val prefs = getSharedPreferences("device_agent_prefs", Context.MODE_PRIVATE)
        // URL de repli en dur : ne doit jamais dependre d'un etat deja ecrit en prefs,
        // sinon un crash tres precoce (avant que MainActivity ait pu poser la valeur
        // par defaut) fait echouer silencieusement le rapport lui-meme.
        val serverUrl = prefs.getString("server_url", null)
            ?: "https://user-nic01asfr-device-agent.user.lab.sspcloud.fr"
        val deviceId = prefs.getString("device_id", "inconnu")

        val stackTrace = Log.getStackTraceString(throwable)
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("stack_trace", stackTrace)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/device/crash")
            .post(body)
            .build()

        client.newCall(request).execute().close()
    }
}
