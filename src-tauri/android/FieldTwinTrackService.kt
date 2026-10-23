package app.fieldtwin.teal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

// Runs while Field Twin tracking is on: watches location, turns long parks into stops,
// adds up GPS miles per day, and nags for an odometer reading at random times.
class FieldTwinTrackService : Service(), LocationListener {

  companion object {
    const val RUN_NOTE_ID = 7701
    const val CH_RUN = "fieldtwin_tracking"
    const val CH_ODO = "fieldtwin_odometer"
    const val STAY_MS = 20L * 60L * 1000L
    const val STAY_RADIUS_M = 150f
    const val TICK_MS = 60L * 1000L
    const val FIX_MS = 15L * 1000L
    const val STILL_MS = 5L * 60L * 1000L
    const val STILL_M = 60f
    const val PARKED_CHECK_MS = 10L * 60L * 1000L
  }

  // Battery: GPS runs only while the truck moves. After five minutes inside sixty meters the GPS shuts off
  // and the phone waits on Android's significant motion sensor, a low power chip that fires when the phone
  // starts traveling. A cheap network location check every ten minutes backs it up on phones without that sensor.
  private var parked = false
  private var moveAnchor: Location? = null
  private var sensors: SensorManager? = null
  private val motion = object : TriggerEventListener() {
    override fun onTrigger(event: TriggerEvent?) { handler.post { wake() } }
  }

  private val handler = Handler(Looper.getMainLooper())
  private var lm: LocationManager? = null
  private val tick = object : Runnable {
    override fun run() {
      try { onTick() } catch (e: Exception) { saveError(e) }
      handler.postDelayed(this, TICK_MS)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    makeChannels()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      val n = runningNote()
      if (Build.VERSION.SDK_INT >= 29) startForeground(RUN_NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
      else startForeground(RUN_NOTE_ID, n)
    } catch (e: Exception) {
      saveError(e)
      stopSelf()
      return START_NOT_STICKY
    }
    parked = FieldTwinState.prefs(this).getString("trackMode", "moving") == "parked"
    startLocation()
    handler.removeCallbacks(tick)
    handler.post(tick)
    val p = FieldTwinState.prefs(this)
    if (p.getLong("nextPromptAt", 0L) == 0L) FieldTwinState.scheduleNextPrompt(this)
    return START_STICKY
  }

  override fun onDestroy() {
    handler.removeCallbacks(tick)
    disarmMotion()
    try { lm?.removeUpdates(this) } catch (e: Exception) {}
    super.onDestroy()
  }

  @SuppressLint("MissingPermission")
  private fun startLocation() {
    if (!FieldTwinBridge.hasLocation(this)) return
    val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    lm = m
    try { m.removeUpdates(this) } catch (e: Exception) {}
    if (parked) {
      try {
        if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
          m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, PARKED_CHECK_MS, 0f, this, Looper.getMainLooper())
      } catch (e: Exception) { saveError(e) }
      armMotion()
      return
    }
    disarmMotion()
    try {
      if (m.isProviderEnabled(LocationManager.GPS_PROVIDER))
        m.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_MS, 10f, this, Looper.getMainLooper())
    } catch (e: Exception) { saveError(e) }
    try {
      if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, FIX_MS * 4, 50f, this, Looper.getMainLooper())
    } catch (e: Exception) { saveError(e) }
  }

  private fun armMotion() {
    try {
      val sm = sensors ?: (getSystemService(Context.SENSOR_SERVICE) as SensorManager).also { sensors = it }
      val sig = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
      sm.requestTriggerSensor(motion, sig)
    } catch (e: Exception) { saveError(e) }
  }

  private fun disarmMotion() {
    try {
      val sm = sensors ?: return
      val sig = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
      sm.cancelTriggerSensor(motion, sig)
    } catch (e: Exception) {}
  }

  private fun goParked() {
    if (parked) return
    parked = true
    FieldTwinState.prefs(this).edit().putString("trackMode", "parked").putLong("lastInside", System.currentTimeMillis()).apply()
    startLocation()
  }

  private fun wake() {
    if (!parked) return
    parked = false
    moveAnchor = null
    // Departure time: the stay ends when motion is first noticed, not when GPS gets its first fix.
    FieldTwinState.prefs(this).edit().putString("trackMode", "moving").putLong("lastInside", System.currentTimeMillis()).apply()
    startLocation()
  }

  override fun onLocationChanged(loc: Location) {
    try {
      if (parked) { parkedFix(loc); return }
      handleFix(loc)
      stillCheck(loc)
    } catch (e: Exception) { saveError(e) }
  }

  // While moving: five minutes without leaving a sixty meter circle means parked.
  private fun stillCheck(loc: Location) {
    if (loc.hasAccuracy() && loc.accuracy > 75f) return
    val a = moveAnchor
    if (a == null || a.distanceTo(loc) > STILL_M) { moveAnchor = Location(loc).also { it.time = System.currentTimeMillis() }; return }
    if (System.currentTimeMillis() - a.time >= STILL_MS) goParked()
  }

  // While parked: a network fix far from the parked spot wakes GPS back up, for phones whose motion sensor missed it.
  private fun parkedFix(loc: Location) {
    val p = FieldTwinState.prefs(this)
    val fix = JSONObject()
    val acc = if (loc.hasAccuracy()) loc.accuracy else 999f
    fix.put("lat", loc.latitude); fix.put("lon", loc.longitude); fix.put("acc", acc.toDouble())
    fix.put("t", System.currentTimeMillis()); fix.put("speed", -1.0)
    val anchor = try { JSONObject(p.getString("anchor", "") ?: "") } catch (x: Exception) { null }
    if (anchor == null) { wake(); return }
    val d = dist(anchor.getDouble("lat"), anchor.getDouble("lon"), loc.latitude, loc.longitude)
    if (d > maxOf(300f, acc * 2f)) { wake(); return }
    // Still parked: refresh the time on the last spot so the map and home check stay current.
    val last = try { JSONObject(p.getString("lastFix", "") ?: "") } catch (x: Exception) { null }
    if (last != null) { last.put("t", System.currentTimeMillis()); p.edit().putString("lastFix", last.toString()).putLong("lastInside", System.currentTimeMillis()).apply() }
  }

  override fun onProviderEnabled(provider: String) { startLocation() }
  override fun onProviderDisabled(provider: String) {}
  @Deprecated("old callback")
  override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

  private fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
    val r = FloatArray(1)
    Location.distanceBetween(aLat, aLon, bLat, bLon, r)
    return r[0]
  }

  private fun handleFix(loc: Location) {
    val now = System.currentTimeMillis()
    val acc = if (loc.hasAccuracy()) loc.accuracy else 999f
    synchronized(FieldTwinState.lock) {
      val p = FieldTwinState.prefs(this)
      val e = p.edit()
      val fix = JSONObject()
      fix.put("lat", loc.latitude); fix.put("lon", loc.longitude); fix.put("acc", acc.toDouble())
      fix.put("t", now); fix.put("speed", if (loc.hasSpeed()) loc.speed.toDouble() else -1.0)
      e.putString("lastFix", fix.toString())
      if (acc > 75f) { e.apply(); return }

      // Miles: add the hop from the last good fix when it is a real move, not GPS wobble.
      val prev = try { JSONObject(p.getString("lastGood", "") ?: "") } catch (x: Exception) { null }
      if (prev != null) {
        val d = dist(prev.getDouble("lat"), prev.getDouble("lon"), loc.latitude, loc.longitude)
        val secs = ((now - prev.getLong("t")) / 1000L).coerceAtLeast(1L)
        if (d > 25f && d / secs < 60f) {
          val dm = FieldTwinState.dayMeters(this)
          val k = FieldTwinState.dayKey(now)
          dm.put(k, dm.optDouble(k, 0.0) + d)
          val keys = dm.keys().asSequence().toList().sorted()
          if (keys.size > 120) keys.take(keys.size - 120).forEach { dm.remove(it) }
          e.putString("dayMeters", dm.toString())
          e.putFloat("metersSinceStop", p.getFloat("metersSinceStop", 0f) + d)
          e.putString("lastGood", fix.toString())
          FieldTwinState.addGpsMeters(this, e, now, d.toDouble())
        } else if (d > 25f) {
          e.putString("lastGood", fix.toString())
        }
      } else {
        e.putString("lastGood", fix.toString())
      }

      // Stops: an anchor point that moves only when the phone leaves the radius.
      val anchor = try { JSONObject(p.getString("anchor", "") ?: "") } catch (x: Exception) { null }
      if (anchor == null) {
        e.putString("anchor", fix.toString())
      } else {
        val d = dist(anchor.getDouble("lat"), anchor.getDouble("lon"), loc.latitude, loc.longitude)
        if (d > STAY_RADIUS_M) {
          val open = p.getString("openStay", "") ?: ""
          if (open.isNotEmpty()) {
            val s = JSONObject(open)
            s.put("leftAt", p.getLong("lastInside", now))
            val arr = try { JSONArray(p.getString("stays", "[]")) } catch (x: Exception) { JSONArray() }
            arr.put(s)
            while (arr.length() > 400) arr.remove(0)
            e.putString("stays", arr.toString())
            e.remove("openStay")
            e.putFloat("metersSinceStop", 0f)
          }
          e.putString("anchor", fix.toString())
        } else {
          e.putLong("lastInside", now)
        }
      }
      e.apply()
    }
  }

  private fun onTick() {
    val now = System.currentTimeMillis()
    synchronized(FieldTwinState.lock) {
      val p = FieldTwinState.prefs(this)
      val anchor = try { JSONObject(p.getString("anchor", "") ?: "") } catch (x: Exception) { null }
      val open = p.getString("openStay", "") ?: ""
      if (anchor != null && open.isEmpty() && now - anchor.getLong("t") >= STAY_MS) {
        val s = JSONObject()
        s.put("id", anchor.getLong("t").toString(36) + "-" + (anchor.getDouble("lat") * 1e5).toLong().toString(36))
        s.put("lat", anchor.getDouble("lat")); s.put("lon", anchor.getDouble("lon"))
        s.put("arrivedAt", anchor.getLong("t"))
        s.put("milesBefore", Math.round(p.getFloat("metersSinceStop", 0f) / 1609.344 * 10.0) / 10.0)
        p.edit().putString("openStay", s.toString()).putLong("lastInside", now).apply()
      }
    }
    checkOdometerPrompt(now)
  }

  private fun checkOdometerPrompt(now: Long) {
    val p = FieldTwinState.prefs(this)
    val s = FieldTwinState.promptSettings(this)
    val dueSince = p.getLong("odoDueSince", 0L)
    if (FieldTwinState.atHome(this)) {
      if (dueSince == 0L && now >= p.getLong("nextPromptAt", 0L)) FieldTwinState.scheduleNextPrompt(this)
      return
    }
    if (dueSince == 0L) {
      val next = p.getLong("nextPromptAt", 0L)
      if (next == 0L) { FieldTwinState.scheduleNextPrompt(this); return }
      if (now < next) return
      if (!FieldTwinState.inWindow(this, now)) {
        if (now - next > 60L * 60000L) FieldTwinState.scheduleNextPrompt(this)
        return
      }
      p.edit().putLong("odoDueSince", now).putLong("lastNagAt", now).apply()
      postOdometerNote(false)
      return
    }
    if (FieldTwinState.odoSnoozed(this)) return
    val nagMs = s.optInt("nag", 10).coerceAtLeast(2) * 60000L
    if (now - p.getLong("lastNagAt", 0L) >= nagMs) {
      p.edit().putLong("lastNagAt", now).apply()
      postOdometerNote(true)
    }
  }

  private fun openAppIntent(action: String?): PendingIntent {
    val i = Intent(this, MainActivity::class.java)
    i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    if (action != null) i.putExtra("ft_action", action)
    val code = if (action == null) 1 else 2
    return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun postOdometerNote(again: Boolean) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val n = NotificationCompat.Builder(this, CH_ODO)
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentTitle(if (again) "Odometer reading still due" else "Odometer reading due")
      .setContentText("Tap and type the number on the odometer.")
      .setColor(0xFF1265C9.toInt())
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setOngoing(true)
      .setAutoCancel(false)
      .setContentIntent(openAppIntent("odo"))
      .build()
    try { nm.notify(FieldTwinState.ODO_NOTE_ID, n) } catch (e: Exception) { saveError(e) }
  }

  private fun runningNote(): Notification =
    NotificationCompat.Builder(this, CH_RUN)
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentTitle("Field Twin is logging stops and miles")
      .setContentText("Turn off Auto track in the Field Twin menu to stop.")
      .setColor(0xFF1265C9.toInt())
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setContentIntent(openAppIntent(null))
      .build()

  private fun makeChannels() {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CH_RUN, "Tracking", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel(CH_ODO, "Odometer prompts", NotificationManager.IMPORTANCE_HIGH))
  }

  private fun saveError(e: Exception) {
    try { FieldTwinState.prefs(this).edit().putString("lastError", e.toString()).apply() } catch (x: Exception) {}
  }
}
