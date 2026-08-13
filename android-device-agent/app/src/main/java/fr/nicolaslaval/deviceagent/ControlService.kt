package fr.nicolaslaval.deviceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Le seul composant qui a un acces reel a l'ecran et aux autres apps.
 *
 * Ne fait rien de lui-meme : execute uniquement les commandes que MainActivity
 * lui transmet (elle-meme recuperees par polling du relais). Pas de reseau ici,
 * separation stricte : ce fichier ne connait que l'API Accessibility.
 */
class ControlService : AccessibilityService() {

    companion object {
        var instance: ControlService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Pas d'ecoute passive dans cette version — uniquement des actions a la demande.
    }

    override fun onInterrupt() {}

    /** Point d'entree unique appele par MainActivity pour executer une commande recue du relais. */
    suspend fun execute(type: String, params: JSONObject): JSONObject {
        return when (type) {
            "dump_ui" -> dumpUi()
            "tap" -> tap(params.getInt("x"), params.getInt("y"))
            "swipe" -> swipe(
                params.getInt("x"), params.getInt("y"),
                params.getInt("x2"), params.getInt("y2")
            )
            "type_text" -> typeText(params.optString("text", ""))
            "key" -> pressKey(params.optString("key", ""))
            "launch_app" -> launchApp(params.optString("package", ""))
            else -> JSONObject().put("ok", false).put("error", "type de commande inconnu: $type")
        }
    }

    private fun dumpUi(): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "aucune fenetre active")
        val nodes = JSONArray()
        collectNodes(root, nodes, depth = 0, maxDepth = 12, maxNodes = 300)
        return JSONObject().put("ok", true).put("tree", nodes)
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: JSONArray, depth: Int, maxDepth: Int, maxNodes: Int) {
        if (depth > maxDepth || out.length() >= maxNodes) return
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank() || node.isClickable || node.isEditable) {
            out.put(
                JSONObject()
                    .put("text", text ?: "")
                    .put("className", node.className?.toString() ?: "")
                    .put("resourceId", node.viewIdResourceName ?: "")
                    .put("clickable", node.isClickable)
                    .put("editable", node.isEditable)
                    .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, depth + 1, maxDepth, maxNodes)
            child.recycle()
        }
    }

    private suspend fun tap(x: Int, y: Int): JSONObject {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val ok = dispatchGestureSuspend(gesture)
        return JSONObject().put("ok", ok).put("action", "tap").put("x", x).put("y", y)
    }

    private suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): JSONObject {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val ok = dispatchGestureSuspend(gesture)
        return JSONObject().put("ok", ok).put("action", "swipe")
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    private fun typeText(text: String): JSONObject {
        val focused = findFocusedEditable(rootInActiveWindow)
            ?: return JSONObject().put("ok", false).put("error", "aucun champ de texte actif")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return JSONObject().put("ok", ok).put("action", "type_text").put("text", text)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun pressKey(key: String): JSONObject {
        val globalAction = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return JSONObject().put("ok", false).put("error", "touche inconnue: $key")
        }
        val ok = performGlobalAction(globalAction)
        return JSONObject().put("ok", ok).put("action", "key").put("key", key)
    }

    private fun launchApp(packageName: String): JSONObject {
        if (packageName.isBlank()) return JSONObject().put("ok", false).put("error", "package manquant")
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return JSONObject().put("ok", false).put("error", "app introuvable: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return JSONObject().put("ok", true).put("action", "launch_app").put("package", packageName)
    }
}
