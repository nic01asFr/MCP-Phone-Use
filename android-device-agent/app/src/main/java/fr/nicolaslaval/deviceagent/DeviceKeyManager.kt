package fr.nicolaslaval.deviceagent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

/**
 * Gere la paire de cles EC de l'appareil dans l'Android Keystore.
 *
 * La cle privee n'est jamais exportable (IsStrongBoxBacked quand disponible,
 * sinon TEE logiciel). Seule la cle publique quitte l'appareil, au moment
 * de l'enrolement. Voir docs/architecture.md a la racine du repo.
 */
class DeviceKeyManager(private val prefs: android.content.SharedPreferences) {

    companion object {
        private const val KEYSTORE_ALIAS = "device-agent-identity"
        private const val PREF_DEVICE_ID = "device_id"
    }

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val deviceId: String
        get() = prefs.getString(PREF_DEVICE_ID, null) ?: run {
            val id = "android-" + UUID.randomUUID().toString().take(12)
            prefs.edit().putString(PREF_DEVICE_ID, id).apply()
            id
        }

    fun hasKeyPair(): Boolean = keyStore.containsAlias(KEYSTORE_ALIAS)

    /** Genere la paire de cles si absente. Idempotent. */
    fun ensureKeyPair() {
        if (hasKeyPair()) return
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    /** Cle publique DER, encodee en base64 — c'est tout ce qui est envoye au serveur. */
    fun publicKeyDerBase64(): String {
        val cert = keyStore.getCertificate(KEYSTORE_ALIAS)
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    /** Signe un nonce (challenge) avec la cle privee — ne quitte jamais le Keystore. */
    fun signNonce(nonce: String): String {
        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(nonce.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }
}
