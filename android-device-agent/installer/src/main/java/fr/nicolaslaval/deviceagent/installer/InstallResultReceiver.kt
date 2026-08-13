package fr.nicolaslaval.deviceagent.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Recoit le resultat de la session PackageInstaller. STATUS_PENDING_USER_ACTION
 * declenche la boite de dialogue systeme standard de confirmation d'installation
 * (toujours affichee, quelle que soit la methode — ce n'est PAS ce que
 * l'installation par session contourne ; elle contourne uniquement le blocage
 * "Parametres restreints" sur Accessibilite/Notifications pour l'app installee).
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmIntent?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "device-agent installe (installation de confiance)", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(context, "Echec installation: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
