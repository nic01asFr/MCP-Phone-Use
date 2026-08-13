package fr.nicolaslaval.deviceagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Client HTTP minimal vers les routes device-XXX du relais (enroll, challenge, session). Pas de MCP ici. */
class RelayClient(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RelayException(response.code, text)
            }
            return if (text.isNotBlank()) JSONObject(text) else JSONObject()
        }
    }

    fun enroll(enrollmentCode: String, deviceId: String, publicKeyDerBase64: String) {
        post(
            "/device/enroll",
            JSONObject()
                .put("enrollment_code", enrollmentCode)
                .put("device_id", deviceId)
                .put("public_key_der_b64", publicKeyDerBase64)
        )
    }

    fun requestChallenge(deviceId: String): String {
        val res = post("/device/challenge", JSONObject().put("device_id", deviceId))
        return res.getString("nonce")
    }

    fun openSession(deviceId: String, nonce: String, signatureBase64: String): String {
        val res = post(
            "/device/session",
            JSONObject()
                .put("device_id", deviceId)
                .put("nonce", nonce)
                .put("signature_b64", signatureBase64)
        )
        return res.getString("session_token")
    }

    fun disconnect(sessionToken: String) {
        post("/device/disconnect", JSONObject().put("session_token", sessionToken))
    }

    fun pollCommands(deviceId: String): JSONObject {
        return post("/device/commands/poll", JSONObject().put("device_id", deviceId))
    }

    fun submitResult(commandId: String, result: JSONObject) {
        post(
            "/device/commands/result",
            JSONObject().put("command_id", commandId).put("result", result)
        )
    }
}

class RelayException(val httpCode: Int, val body: String) :
    Exception("Erreur relais HTTP $httpCode: $body")
