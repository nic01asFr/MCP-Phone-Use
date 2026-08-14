package fr.nicolaslaval.deviceagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Capture d'ecran reelle via MediaProjection. Separe de ControlService
 * (AccessibilityService) car le consentement est un mecanisme different :
 * popup systeme distincte, redemandee a chaque session par l'OS (Android 14+),
 * jamais groupable avec l'activation d'Accessibilite. Voir docs/architecture.md.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "device_agent_screencap"
        private const val NOTIF_ID = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var instance: ScreenCaptureService? = null
            private set

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData != null && mediaProjection == null) {
            setUpProjection(resultCode, resultData)
        }
        instance = this
        return START_STICKY
    }

    private fun setUpProjection(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, resultData) ?: return
        mediaProjection = projection

        val metrics = DisplayMetrics()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "device-agent-capture", width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    /** Capture la frame courante et la retourne encodee en JPEG. Suspend jusqu'a la
     * prochaine frame disponible (timeout court) plutot que de bloquer indefiniment. */
    suspend fun captureJpeg(): ByteArray? {
        val reader = imageReader ?: return null
        return suspendCancellableCoroutine { cont ->
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = android.graphics.Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride, image.height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)

                val out = ByteArrayOutputStream()
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                if (cont.isActive) cont.resume(out.toByteArray())
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            } finally {
                image.close()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "device-agent capture d'ecran", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("device-agent")
            .setContentText("Capture d'ecran active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
