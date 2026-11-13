package com.example.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.example.data.model.AppUsageStat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class BatterySnapshot(
  val levelPercent: Int,
  val isCharging: Boolean,
)

data class UsageSnapshot(
  val hasPermission: Boolean,
  val totalMinutesToday: Long,
  val topApps: List<AppUsageStat>,
)

data class LocationSnapshot(
  val latitude: Double,
  val longitude: Double,
  val label: String,
)

object DeviceTelemetryHelper {

  fun readBattery(context: Context): BatterySnapshot {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 85
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return BatterySnapshot(levelPercent = pct, isCharging = isCharging)
  }

  fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
      @Suppress("DEPRECATION")
      appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
  }

  fun readTodayUsageStats(context: Context): UsageSnapshot {
    if (!hasUsageStatsPermission(context)) {
      return UsageSnapshot(hasPermission = false, totalMinutesToday = 0L, topApps = emptyList())
    }
    val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
      ?: return UsageSnapshot(hasPermission = true, totalMinutesToday = 0L, topApps = emptyList())

    val cal = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val now = System.currentTimeMillis()
    val stats = usageManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, now)
      .orEmpty()
      .filter { it.totalTimeInForeground >= 60_000L }
      .sortedByDescending { it.totalTimeInForeground }

    val pm = context.packageManager
    val mapped = stats.take(8).map { stat ->
      val appLabel = runCatching {
        val info = pm.getApplicationInfo(stat.packageName, 0)
        pm.getApplicationLabel(info).toString()
      }.getOrElse { stat.packageName.substringAfterLast('.') }
      val minutes = (stat.totalTimeInForeground / 60_000L).coerceAtLeast(1L)
      AppUsageStat(appName = appLabel, minutesUsed = minutes, category = "App")
    }
    val totalMinutes = (stats.sumOf { it.totalTimeInForeground } / 60_000L).coerceAtMost(1440L)
    return UsageSnapshot(hasPermission = true, totalMinutesToday = totalMinutes, topApps = mapped)
  }

  fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  @SuppressLint("MissingPermission")
  suspend fun fetchConsentLocation(context: Context): LocationSnapshot? = withContext(Dispatchers.IO) {
    if (!hasLocationPermission(context)) return@withContext null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cts = CancellationTokenSource()
    val loc = runCatching {
      client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
    }.getOrNull() ?: runCatching { client.lastLocation.await() }.getOrNull() ?: return@withContext null

    val label = runCatching {
      @Suppress("DEPRECATION")
      val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
      val addr = addresses?.firstOrNull()
      listOfNotNull(addr?.subLocality, addr?.locality, addr?.adminArea).joinToString(", ")
        .ifBlank { "${String.format(Locale.US, "%.4f", loc.latitude)}, ${String.format(Locale.US, "%.4f", loc.longitude)}" }
    }.getOrElse {
      "${String.format(Locale.US, "%.4f", loc.latitude)}, ${String.format(Locale.US, "%.4f", loc.longitude)}"
    }

    LocationSnapshot(
      latitude = loc.latitude,
      longitude = loc.longitude,
      label = label.take(120),
    )
  }
}
