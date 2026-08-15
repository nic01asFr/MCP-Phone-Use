package fr.nicolaslaval.deviceagent

import android.app.Application
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
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

        // Capte aussi les sorties de processus qui echappent au handler ci-dessus —
        // RemoteServiceException (ex: ForegroundServiceDidNotStartInTimeException),
        // crash natif, ANR — via l'API publique ApplicationExitInfo (Android 11+),
        // sans jamais avoir besoin d'ADB.
        reportLastExitReasonIfNew()
    }

    private fun reportLastExitReasonIfNew() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 1)
            val last = exits.firstOrNull() ?: return

            // Diagnostic actif : on rapporte TOUTE raison de sortie non deja vue,
            // sans filtrer par type — on ne sait pas encore laquelle Android attribue
            // reellement a ce plantage, autant tout voir plutot que de re-filtrer a l'aveugle.
            val prefs = getSharedPreferences("device_agent_prefs", MODE_PRIVATE)
            val alreadyReported = prefs.getLong("last_exit_reported_ts", -1L)
            if (last.timestamp == alreadyReported) return

            val traceText = try {
                last.traceInputStream?.bufferedReader()?.readText()
            } catch (_: Exception) {
                null
            }

            val summary = buildString {
                append("=== SORTIE DE PROCESSUS (ApplicationExitInfo) ===\n")
                append("reason=${last.reason} (${reasonName(last.reason)})\n")
                append("description=${last.description}\n")
                append("timestamp=${last.timestamp}\n")
                if (traceText != null) {
                    append("--- trace ---\n")
                    append(traceText)
                }
            }

            reportCrashBlocking(RuntimeException(summary))
            prefs.edit().putLong("last_exit_reported_ts", last.timestamp).apply()
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        else -> "autre ($reason)"
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
