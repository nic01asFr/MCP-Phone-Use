package fr.nic01asfr.deviceagent

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Client HTTP minimal (pas de dependance externe) pour parler au relais device-agent. */
object ApiClient {

    class ApiException(message: String, val code: Int) : Exception(message)

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        if (code !in 200..299) {
            throw ApiException(text, code)
        }
        return JSONObject(text)
    }

    fun enroll(serverUrl: String, enrollmentCode: String, deviceId: String, publicKeyDerB64: String) {
        postJson(
            "$serverUrl/device/enroll",
            JSONObject()
                .put("enrollment_code", enrollmentCode)
                .put("device_id", deviceId)
                .put("public_key_der_b64", publicKeyDerB64)
        )
    }

    fun requestChallenge(serverUrl: String, deviceId: String): String {
        val resp = postJson("$serverUrl/device/challenge", JSONObject().put("device_id", deviceId))
        return resp.getString("nonce")
    }

    fun openSession(serverUrl: String, deviceId: String, nonce: String, signatureB64: String): String {
        val resp = postJson(
            "$serverUrl/device/session",
            JSONObject()
                .put("device_id", deviceId)
                .put("nonce", nonce)
                .put("signature_b64", signatureB64)
        )
        return resp.getString("session_token")
    }

    fun disconnect(serverUrl: String, sessionToken: String) {
        postJson("$serverUrl/device/disconnect", JSONObject().put("session_token", sessionToken))
    }
}
