package app.fieldtwin.teal

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

// Stored state shared by the tracking service and the page bridge.
object FieldTwinState {
  val lock = Any()
  const val ODO_NOTE_ID = 7702

  fun prefs(ctx: Context): SharedPreferences =
    ctx.applicationContext.getSharedPreferences("fieldtwin_native", Context.MODE_PRIVATE)

  fun dayKey(t: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = t
    return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
  }

  fun dayMeters(ctx: Context): JSONObject =
    try { JSONObject(prefs(ctx).getString("dayMeters", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

  // GPS counter: a running total of GPS meters, like a second odometer, with a time log so the page
  // can ask how far GPS counted between any two odometer readings and calibrate against them.
  fun gpsTotal(ctx: Context): Double = prefs(ctx).getString("gpsTotal", "0")?.toDoubleOrNull() ?: 0.0

  fun gpsLog(ctx: Context): JSONArray =
    try { JSONArray(prefs(ctx).getString("gpsLog", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

  fun addGpsMeters(ctx: Context, e: SharedPreferences.Editor, now: Long, meters: Double) {
    val p = prefs(ctx)
    val before = gpsTotal(ctx)
    val total = before + meters
    e.putString("gpsTotal", total.toString())
    val log = gpsLog(ctx)
    val lastT = if (log.length() > 0) log.getJSONArray(log.length() - 1).getLong(0) else 0L
    // A point marks where driving resumed after a quiet spell, so time spent parked is not smeared into the drive.
    if (now - lastT > 5L * 60000L) log.put(JSONArray().put(now - 1000L).put(before))
    if (now - lastT >= 60000L || log.length() == 0) log.put(JSONArray().put(now).put(total))
    else log.getJSONArray(log.length() - 1).put(0, now).put(1, total)
    while (log.length() > 20000) log.remove(0)
    e.putString("gpsLog", log.toString())
    if (p.getLong("gpsSince", 0L) == 0L) e.putLong("gpsSince", now)
  }

  // GPS meters counted by time t, read off the log. Minus one when t is before the log starts.
  fun gpsAt(ctx: Context, t: Long): Double {
    val log = gpsLog(ctx)
    if (log.length() == 0) return -1.0
    val since = prefs(ctx).getLong("gpsSince", 0L)
    if (since == 0L || t < since) return -1.0
    var lo = 0; var hi = log.length() - 1
    if (t >= log.getJSONArray(hi).getLong(0)) return gpsTotal(ctx)
    if (t <= log.getJSONArray(0).getLong(0)) return log.getJSONArray(0).getDouble(1)
    while (hi - lo > 1) { val m = (lo + hi) / 2; if (log.getJSONArray(m).getLong(0) <= t) lo = m else hi = m }
    val a = log.getJSONArray(lo); val b = log.getJSONArray(hi)
    val ta = a.getLong(0); val tb = b.getLong(0)
    if (tb <= ta) return a.getDouble(1)
    return a.getDouble(1) + (b.getDouble(1) - a.getDouble(1)) * (t - ta).toDouble() / (tb - ta).toDouble()
  }

  // Home: odometer prompts wait while the phone sits inside this circle.
  fun atHome(ctx: Context): Boolean {
    try {
      val p = prefs(ctx)
      val h = JSONObject(p.getString("home", "") ?: "")
      val f = JSONObject(p.getString("lastFix", "") ?: "")
      if (System.currentTimeMillis() - f.getLong("t") > 30L * 60000L) return false
      val r = FloatArray(1)
      android.location.Location.distanceBetween(h.getDouble("lat"), h.getDouble("lon"), f.getDouble("lat"), f.getDouble("lon"), r)
      return r[0] <= h.optDouble("r", 200.0)
    } catch (e: Exception) { return false }
  }

  fun promptSettings(ctx: Context): JSONObject {
    val d = JSONObject()
    d.put("on", true); d.put("start", 7); d.put("end", 18); d.put("minGap", 60); d.put("maxGap", 150); d.put("nag", 10)
    try {
      val s = JSONObject(prefs(ctx).getString("promptSettings", "{}") ?: "{}")
      val it = s.keys()
      while (it.hasNext()) { val k = it.next(); d.put(k, s.get(k)) }
    } catch (e: Exception) {}
    return d
  }

  fun inWindow(ctx: Context, t: Long): Boolean {
    val s = promptSettings(ctx)
    if (!s.optBoolean("on", true)) return false
    val c = Calendar.getInstance()
    c.timeInMillis = t
    val h = c.get(Calendar.HOUR_OF_DAY)
    return h >= s.optInt("start", 7) && h < s.optInt("end", 18)
  }

  fun scheduleNextPrompt(ctx: Context) {
    val s = promptSettings(ctx)
    val lo = s.optInt("minGap", 60).coerceAtLeast(5)
    val hi = s.optInt("maxGap", 150).coerceAtLeast(lo + 1)
    val mins = Random.nextInt(lo, hi + 1)
    prefs(ctx).edit().putLong("nextPromptAt", System.currentTimeMillis() + mins * 60000L).apply()
  }

  fun odoSaved(ctx: Context) {
    prefs(ctx).edit().putLong("odoDueSince", 0L).putLong("lastNagAt", 0L).apply()
    try {
      val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.cancel(ODO_NOTE_ID)
    } catch (e: Exception) {}
    scheduleNextPrompt(ctx)
  }
}
