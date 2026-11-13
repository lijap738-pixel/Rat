package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object SessionBroadcastBus {
  const val ACTION_STOP_FROM_NOTIFICATION = "com.aistudio.safenest.ACTION_STOP_SESSION"

  private val _stopRequested = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val stopRequested = _stopRequested.asSharedFlow()

  private val _audioLevelDb = MutableStateFlow(0)
  val audioLevelDb = _audioLevelDb.asStateFlow()

  private val _screenFrameBase64 = MutableStateFlow<String?>(null)
  val screenFrameBase64 = _screenFrameBase64.asStateFlow()

  fun notifyStop(sessionType: String) {
    _stopRequested.tryEmit(sessionType)
  }

  fun updateAudioLevel(db: Int) {
    _audioLevelDb.value = db.coerceIn(0, 100)
  }

  fun updateScreenFrame(base64: String?) {
    _screenFrameBase64.value = base64
  }
}

internal object SafeNestNotifications {
  const val CHANNEL_ID = "safenest_live_sessions"

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      if (manager.getNotificationChannel(CHANNEL_ID) == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "SafeNest Live Consent Sessions",
          NotificationManager.IMPORTANCE_HIGH,
        ).apply {
          description = "Transparent indicator when Camera, Microphone, or Screen Sharing is active."
        }
        manager.createNotificationChannel(channel)
      }
    }
  }

  fun buildActiveNotification(
    context: Context,
    title: String,
    body: String,
    stopIntent: PendingIntent,
  ): Notification {
    ensureChannel(context)
    val openIntent = PendingIntent.getActivity(
      context,
      100,
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(openIntent)
      .addAction(0, "Stop Sharing", stopIntent)
      .build()
  }
}

class SafeNestCameraService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == SessionBroadcastBus.ACTION_STOP_FROM_NOTIFICATION) {
      SessionBroadcastBus.notifyStop("camera")
      stopSelf()
      return START_NOT_STICKY
    }

    val stopPending = PendingIntent.getService(
      this,
      201,
      Intent(this, SafeNestCameraService::class.java).apply {
        action = SessionBroadcastBus.ACTION_STOP_FROM_NOTIFICATION
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = SafeNestNotifications.buildActiveNotification(
      context = this,
      title = "SafeNest Camera Active",
      body = "Live camera session approved and visible to parent. Tap Stop Sharing anytime.",
      stopIntent = stopPending,
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    } else {
      startForeground(NOTIF_ID, notification)
    }
    return START_NOT_STICKY
  }

  companion object {
    private const val NOTIF_ID = 4101
  }
}

class SafeNestMicService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var meterJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == SessionBroadcastBus.ACTION_STOP_FROM_NOTIFICATION) {
      SessionBroadcastBus.notifyStop("microphone")
      stopSelf()
      return START_NOT_STICKY
    }

    val stopPending = PendingIntent.getService(
      this,
      202,
      Intent(this, SafeNestMicService::class.java).apply {
        action = SessionBroadcastBus.ACTION_STOP_FROM_NOTIFICATION
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = SafeNestNotifications.buildActiveNotification(
      context = this,
      title = "SafeNest Microphone Active",
      body = "Live consent-based audio session in progress (never saved or recorded).",
      stopIntent = stopPending,
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIF_ID, notification)
    }

    startAudioLevelMeter()
    return START_NOT_STICKY
  }

  @SuppressLint("MissingPermission")
  private fun startAudioLevelMeter() {
    meterJob?.cancel()
    meterJob = serviceScope.launch {
      if (ContextCompat.checkSelfPermission(this@SafeNestMicService, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return@launch
      }
      val sampleRate = 16000
      val bufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1024)
      val recorder = runCatching {
        AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
      }.getOrNull() ?: return@launch

      val buffer = ShortArray(bufSize)
      runCatching {
        recorder.startRecording()
        while (isActive) {
          val read = recorder.read(buffer, 0, buffer.size)
          if (read > 0) {
            var sumSq = 0.0
            for (i in 0 until read) {
              val sample = buffer[i].toDouble()
              sumSq += sample * sample
            }
            val rms = sqrt(sumSq / read)
            val db = if (rms > 1.0) (20.0 * log10(rms / 32767.0) + 90.0).toInt().coerceIn(5, 100) else 5
            SessionBroadcastBus.updateAudioLevel(db)
          }
          delay(350L)
        }
      }
      runCatching {
        recorder.stop()
        recorder.release()
      }
      SessionBroadcastBus.updateAudioLevel(0)
    }
  }

  override fun onDestroy() {
    meterJob?.cancel()
    serviceScope.cancel()
    SessionBroadcastBus.updateAudioLevel(0)
    super.onDestroy()
  }

  companion object {
    private const val NOTIF_ID = 4102
  }
}

class SafeNestScreenShareService : Service() {
  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private val handler = Handler(Looper.getMainLooper())
  private var lastFrameMs = 0L

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == SessionBroadcastBus.ACTION_STOP_FROM_NOTIFICATION) {
      SessionBroadcastBus.notifyStop("screen")
      stopProjection()
      stopSelf()
      return START_NOT_STICKY
    }

    val stopPending = PendingIntent.getService(
      this,
      203,
      Intent(this, SafeNestScreenShareService::class.java).apply {
        action = SessionBroadcastBus.ACTION_STOP_FROM_NOTIFICATION
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = SafeNestNotifications.buildActiveNotification(
      context = this,
      title = "SafeNest Screen Sharing Active",
      body = "Your screen is shared with your parent. Tap Stop Sharing anytime.",
      stopIntent = stopPending,
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
      startForeground(NOTIF_ID, notification)
    }

    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
    @Suppress("DEPRECATION")
    val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
    if (resultCode == Activity.RESULT_OK && resultData != null) {
      startProjection(resultCode, resultData)
    }
    return START_NOT_STICKY
  }

  private fun startProjection(resultCode: Int, data: Intent) {
    stopProjection()
    val manager = getSystemService(MediaProjectionManager::class.java) ?: return
    val projection = manager.getMediaProjection(resultCode, data) ?: return
    mediaProjection = projection

    projection.registerCallback(
      object : MediaProjection.Callback() {
        override fun onStop() {
          SessionBroadcastBus.notifyStop("screen")
          stopProjection()
          stopSelf()
        }
      },
      handler,
    )

    val width = 480
    val height = 854
    val density = resources.displayMetrics.densityDpi

    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    imageReader = reader
    reader.setOnImageAvailableListener(
      { r ->
        val now = System.currentTimeMillis()
        val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
        if (now - lastFrameMs < 900L) {
          image.close()
          return@setOnImageAvailableListener
        }
        lastFrameMs = now
        runCatching {
          val plane = image.planes[0]
          val buffer = plane.buffer
          val pixelStride = plane.pixelStride
          val rowStride = plane.rowStride
          val rowPadding = rowStride - pixelStride * width
          val bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
          bmp.copyPixelsFromBuffer(buffer)
          val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
          if (cropped !== bmp) bmp.recycle()
          val out = ByteArrayOutputStream()
          cropped.compress(Bitmap.CompressFormat.JPEG, 55, out)
          cropped.recycle()
          val bytes = out.toByteArray()
          if (bytes.size <= 95_000) {
            SessionBroadcastBus.updateScreenFrame(Base64.encodeToString(bytes, Base64.NO_WRAP))
          }
        }
        image.close()
      },
      handler,
    )

    virtualDisplay = projection.createVirtualDisplay(
      "SafeNestScreenShare",
      width,
      height,
      density,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      reader.surface,
      null,
      handler,
    )
  }

  private fun stopProjection() {
    runCatching { virtualDisplay?.release() }
    runCatching { imageReader?.close() }
    runCatching { mediaProjection?.stop() }
    virtualDisplay = null
    imageReader = null
    mediaProjection = null
    SessionBroadcastBus.updateScreenFrame(null)
  }

  override fun onDestroy() {
    stopProjection()
    super.onDestroy()
  }

  companion object {
    private const val NOTIF_ID = 4103
    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
  }
}
