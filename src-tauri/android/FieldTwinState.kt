package app.fieldtwin.teal

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
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
