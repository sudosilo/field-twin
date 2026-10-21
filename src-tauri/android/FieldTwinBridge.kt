package app.fieldtwin.teal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

// Everything the Field Twin page can ask the phone for, reached from JavaScript as window.FieldTwinNative.
class FieldTwinBridge(private val activity: Activity) {

  companion object {
    const val PERM_CODE = 4417
    @Volatile var helloSeen = false

    fun takeIntent(ctx: Context, intent: Intent?) {
      if (intent == null) return
      val fromExtra = intent.getStringExtra("ft_action")
      val fromLink = intent.data?.host
      val action = fromExtra ?: fromLink
      if (action == "odo") FieldTwinState.prefs(ctx).edit().putString("pendingAction", "odo").apply()
    }

    fun hasLocation(ctx: Context): Boolean =
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun askAndStart(activity: Activity) {
      val need = mutableListOf<String>()
      if (!hasLocation(activity)) {
        need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
      }
      if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        need.add(Manifest.permission.POST_NOTIFICATIONS)
      }
      if (need.isEmpty()) startIfAllowed(activity)
      else ActivityCompat.requestPermissions(activity, need.toTypedArray(), PERM_CODE)
    }

    // Asks Android once to leave Field Twin out of battery saving so tracking keeps running with the screen off.
    fun askBatteryOnce(ctx: Context) {
      try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
        val p = FieldTwinState.prefs(ctx)
        if (p.getBoolean("batteryAsked", false)) return
        p.edit().putBoolean("batteryAsked", true).apply()
        val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName))
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
      } catch (e: Exception) {}
    }

    fun isBatteryFree(ctx: Context): Boolean = try {
      (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) { false }

    fun startIfAllowed(ctx: Context) {
      if (!hasLocation(ctx)) return
      if (!FieldTwinState.prefs(ctx).getBoolean("trackingOn", true)) return
      try {
        val i = Intent(ctx, FieldTwinTrackService::class.java)
        ContextCompat.startForegroundService(ctx, i)
        askBatteryOnce(ctx)
      } catch (e: Exception) {
        FieldTwinState.prefs(ctx).edit().putString("lastError", e.toString()).apply()
      }
    }
  }

  @JavascriptInterface
  fun hello(): String {
    helloSeen = true
    return "field-twin-native-1"
  }

  @JavascriptInterface
  fun isTracking(): Boolean = FieldTwinState.prefs(activity).getBoolean("trackingOn", true)

  @JavascriptInterface
  fun setTracking(on: Boolean) {
    FieldTwinState.prefs(activity).edit().putBoolean("trackingOn", on).apply()
    activity.runOnUiThread {
      if (on) askAndStart(activity)
      else activity.stopService(Intent(activity, FieldTwinTrackService::class.java))
    }
  }

  @JavascriptInterface
  fun hasPermission(): Boolean = hasLocation(activity)

  @JavascriptInterface
  fun lastFix(): String = FieldTwinState.prefs(activity).getString("lastFix", "") ?: ""

  @JavascriptInterface
  fun getStays(): String = FieldTwinState.prefs(activity).getString("stays", "[]") ?: "[]"

  @JavascriptInterface
  fun openStay(): String = FieldTwinState.prefs(activity).getString("openStay", "") ?: ""

  @JavascriptInterface
  fun ackStays(idsJson: String) {
    synchronized(FieldTwinState.lock) {
      val ids = HashSet<String>()
      try {
        val a = JSONArray(idsJson)
        for (i in 0 until a.length()) ids.add(a.getString(i))
      } catch (e: Exception) { return }
      val p = FieldTwinState.prefs(activity)
      val old = try { JSONArray(p.getString("stays", "[]")) } catch (e: Exception) { JSONArray() }
      val keep = JSONArray()
      for (i in 0 until old.length()) {
        val o = old.optJSONObject(i) ?: continue
        if (!ids.contains(o.optString("id"))) keep.put(o)
      }
      p.edit().putString("stays", keep.toString()).apply()
    }
  }

  @JavascriptInterface
  fun dayMiles(day: String): Double {
    val m = FieldTwinState.dayMeters(activity)
    return Math.round(m.optDouble(day, 0.0) / 1609.344 * 10.0) / 10.0
  }

  @JavascriptInterface
  fun takeAction(): String {
    val p = FieldTwinState.prefs(activity)
    val a = p.getString("pendingAction", "") ?: ""
    if (a.isNotEmpty()) p.edit().remove("pendingAction").apply()
    return a
  }

  @JavascriptInterface
  fun odoDue(): Boolean = FieldTwinState.prefs(activity).getLong("odoDueSince", 0L) > 0L

  @JavascriptInterface
  fun odoSaved() {
    FieldTwinState.odoSaved(activity)
  }

  @JavascriptInterface
  fun getPromptSettings(): String = FieldTwinState.promptSettings(activity).toString()

  @JavascriptInterface
  fun setPromptSettings(json: String) {
    FieldTwinState.prefs(activity).edit().putString("promptSettings", json).apply()
    FieldTwinState.scheduleNextPrompt(activity)
  }

  @JavascriptInterface
  fun trackMode(): String = FieldTwinState.prefs(activity).getString("trackMode", "moving") ?: "moving"

  @JavascriptInterface
  fun setHome(json: String) {
    FieldTwinState.prefs(activity).edit().putString("home", json).apply()
  }

  @JavascriptInterface
  fun promptNow() {
    FieldTwinState.prefs(activity).edit().putLong("nextPromptAt", System.currentTimeMillis()).apply()
  }

  @JavascriptInterface
  fun gpsAt(t: Double): Double = synchronized(FieldTwinState.lock) { FieldTwinState.gpsAt(activity, t.toLong()) }

  @JavascriptInterface
  fun gpsNow(): Double = FieldTwinState.gpsTotal(activity)

  @JavascriptInterface
  fun batteryFree(): Boolean = isBatteryFree(activity)

  @JavascriptInterface
  fun lastError(): String = FieldTwinState.prefs(activity).getString("lastError", "") ?: ""
}
