package fr.nicolaslaval.deviceagent.installer

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App minimale, volontairement sans AccessibilityService ni MediaProjection :
 * son seul role est de televerser une APK et de l'installer via l'API
 * PackageInstaller en mode session — la meme methode que Play Store/F-Droid,
 * qui evite le blocage "Parametres restreints" sur l'app installee ensuite.
 *
 * Cette app elle-meme n'a jamais besoin d'Accessibilite : son propre sideload
 * n'est donc jamais concerne par cette restriction.
 */
class InstallerActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var apkUrlInput: EditText
    private lateinit var installButton: TextView

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_installer)

        statusText = findViewById(R.id.statusText)
        apkUrlInput = findViewById(R.id.apkUrlInput)
        installButton = findViewById(R.id.installButton)

        installButton.setOnClickListener { onInstallClicked() }
    }

    private fun onInstallClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            updateStatus("Autorisation d'installer des apps inconnues requise — ouverture des reglages...")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        val url = apkUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            updateStatus("URL manquante")
            return
        }

        lifecycleScope.launch {
            try {
                updateStatus("Telechargement...")
                val apkFile = withContext(Dispatchers.IO) { downloadApk(url) }
                updateStatus("Installation (session)...")
                withContext(Dispatchers.IO) { installViaSession(apkFile) }
                updateStatus("Installation lancee — confirme la boite de dialogue systeme si elle apparait")
            } catch (e: Exception) {
                updateStatus("Erreur: ${e.message}")
            }
        }
    }

    private fun downloadApk(url: String): File {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            val expectedSize = response.body!!.contentLength()  // -1 si inconnue
            val file = File(cacheDir, "download.apk")
            var written = 0L
            response.body!!.byteStream().use { input ->
                file.outputStream().use { output ->
                    written = input.copyTo(output)
                }
            }
            // Verification d'integrite : un telechargement mobile interrompu peut se
            // terminer "sans exception" mais produire un fichier tronque — on le
            // detecte explicitement plutot que de laisser PackageInstaller echouer
            // avec un message opaque (INSTALL_PARSE_FAILED_NOT_APK).
            if (expectedSize > 0 && written != expectedSize) {
                file.delete()
                throw RuntimeException(
                    "telechargement incomplet ($written/$expectedSize octets) — reseau interrompu, reessaie"
                )
            }
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            if (!isZip) {
                file.delete()
                throw RuntimeException("fichier telecharge invalide (pas une APK) — reessaie")
            }
            return file
        }
    }

    private fun installViaSession(apkFile: File) {
        val packageInstaller = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.openWrite("package", 0, apkFile.length()).use { out ->
            apkFile.inputStream().use { it.copyTo(out) }
            session.fsync(out)
        }

        val intent = Intent(this, InstallResultReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(this, sessionId, intent, flags)

        session.commit(pendingIntent.intentSender)
        session.close()
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }
}
