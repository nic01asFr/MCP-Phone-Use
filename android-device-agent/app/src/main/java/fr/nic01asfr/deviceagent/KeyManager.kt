package fr.nic01asfr.deviceagent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

/**
 * Cle EC P-256 dans l'Android Keystore. La cle privee n'est jamais exportable :
 * seule sa cle publique quitte l'appareil (a l'enrolement), et seules des
 * signatures (jamais la cle elle-meme) sont produites ensuite.
 */
object KeyManager {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "device-agent-identity"

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun ensureKeyExists() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    /** Cle publique en DER (SubjectPublicKeyInfo), base64 standard — format attendu par le relais. */
    fun publicKeyDerBase64(): String {
        ensureKeyExists()
        val ks = keyStore()
        val cert = ks.getCertificate(ALIAS)
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    /** Signe le nonce (UTF-8) avec la cle privee — ECDSA/SHA256, retourne la signature en base64. */
    fun signNonce(nonce: String): String {
        ensureKeyExists()
        val ks = keyStore()
        val privateKey = ks.getKey(ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(nonce.toByteArray(Charsets.UTF_8))
        val sig = signature.sign()
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }
}
