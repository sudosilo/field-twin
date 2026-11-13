package app.fieldtwin.teal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

// Wireless site survey for a locker: one pass that lists every Wi-Fi network and every Bluetooth
// low energy device the phone can hear, with signal strength. The page starts it and then reads
// the result back as JSON. Nothing is connected to and nothing is sent anywhere by this code.
object FieldTwinRadio {
  const val PERM_CODE = 4421
  private const val SCAN_MS = 9000L

  @Volatile private var state = "idle"
  @Volatile private var note = ""
  @Volatile private var startedAt = 0L
  private val wifi = JSONArray()
  private val bt = HashMap<String, JSONObject>()
  private val lock = Any()
  private var wifiReceiver: BroadcastReceiver? = null
  private var btCallback: ScanCallback? = null
  private var pending = false

  private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

  private fun missing(c: Context): Array<String> {
    val need = ArrayList<String>()
    if (!has(c, Manifest.permission.ACCESS_FINE_LOCATION)) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
      if (!has(c, Manifest.permission.BLUETOOTH_SCAN)) need.add(Manifest.permission.BLUETOOTH_SCAN)
      if (!has(c, Manifest.permission.BLUETOOTH_CONNECT)) need.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33 && !has(c, Manifest.permission.NEARBY_WIFI_DEVICES)) need.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    return need.toTypedArray()
  }

  fun start(activity: Activity): String {
    if (state == "scanning") return "scanning"
    val need = missing(activity)
    if (need.isNotEmpty()) {
      pending = true
      state = "asking"
      activity.runOnUiThread { ActivityCompat.requestPermissions(activity, need, PERM_CODE) }
      return "asking"
    }
    activity.runOnUiThread { run(activity) }
    return "scanning"
  }

  fun afterPermission(activity: Activity) {
    if (!pending) return
    pending = false
    if (!has(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
      state = "done"
      note = "Location permission was refused, and Android will not list Wi-Fi networks without it."
      synchronized(lock) { while (wifi.length() > 0) wifi.remove(0); bt.clear() }
      startedAt = System.currentTimeMillis()
      return
    }
    run(activity)
  }

  @SuppressLint("MissingPermission")
  private fun run(activity: Activity) {
    val app = activity.applicationContext
    synchronized(lock) { while (wifi.length() > 0) wifi.remove(0); bt.clear() }
    state = "scanning"
    note = ""
    startedAt = System.currentTimeMillis()
    val notes = ArrayList<String>()

    // Wi-Fi
    val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    if (wm == null) notes.add("This phone has no Wi-Fi radio to scan with.")
    else {
      if (!wm.isWifiEnabled) notes.add("Wi-Fi is turned off, so the Wi-Fi list may be empty or old.")
      val r = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { readWifi(wm) }
      }
      wifiReceiver = r
      try {
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, f)
      } catch (e: Exception) {}
      val ok = try { wm.startScan() } catch (e: Exception) { false }
      if (!ok) notes.add("Android held back a fresh Wi-Fi scan, so this is its most recent list.")
      readWifi(wm)
    }

    // Bluetooth low energy
    val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val scanner = try { if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null } catch (e: Exception) { null }
    if (scanner == null) notes.add("Bluetooth is turned off, so no Bluetooth devices were listed.")
    else {
      val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { addBt(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { for (x in results) addBt(x) }
        override fun onScanFailed(errorCode: Int) { note = (note + " Bluetooth scan failed with code " + errorCode + ".").trim() }
      }
      btCallback = cb
      try {
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
      } catch (e: Exception) { notes.add("Bluetooth scan would not start.") }
    }
    note = notes.joinToString(" ")

    Handler(Looper.getMainLooper()).postDelayed({
      try { if (wm != null) readWifi(wm) } catch (e: Exception) {}
      try { wifiReceiver?.let { app.unregisterReceiver(it) } } catch (e: Exception) {}
      wifiReceiver = null
      try { btCallback?.let { scanner?.stopScan(it) } } catch (e: Exception) {}
      btCallback = null
      state = "done"
    }, SCAN_MS)
  }

  @SuppressLint("MissingPermission")
  private fun readWifi(wm: WifiManager) {
    val list = try { wm.scanResults } catch (e: Exception) { null } ?: return
    synchronized(lock) {
      while (wifi.length() > 0) wifi.remove(0)
      for (r in list) {
        val o = JSONObject()
        o.put("ssid", r.SSID ?: "")
        o.put("bssid", r.BSSID ?: "")
        o.put("rssi", r.level)
        o.put("freq", r.frequency)
        o.put("width", when (r.channelWidth) { 0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320; else -> 20 })
        o.put("sec", r.capabilities ?: "")
        wifi.put(o)
      }
    }
  }

  private fun addBt(r: ScanResult) {
    val addr = try { r.device?.address } catch (e: Exception) { null } ?: return
    val rec = r.scanRecord
    synchronized(lock) {
      val o = bt[addr] ?: JSONObject().also { bt[addr] = it; it.put("addr", addr); it.put("seen", 0); it.put("rssi", -200) }
      o.put("seen", o.optInt("seen") + 1)
      if (r.rssi > o.optInt("rssi")) o.put("rssi", r.rssi)
      val name = rec?.deviceName
      if (!name.isNullOrEmpty()) o.put("name", name)
      val m = rec?.manufacturerSpecificData
      if (m != null && m.size() > 0) o.put("mfg", m.keyAt(0))
      val u = rec?.serviceUuids
      if (u != null && u.isNotEmpty()) o.put("svc", u[0].uuid.toString())
      o.put("conn", if (Build.VERSION.SDK_INT >= 26) r.isConnectable else true)
    }
  }

  fun result(): String {
    val o = JSONObject()
    o.put("state", state)
    o.put("note", note)
    o.put("at", startedAt)
    synchronized(lock) {
      o.put("wifi", JSONArray(wifi.toString()))
      val b = JSONArray()
      for (v in bt.values) b.put(JSONObject(v.toString()))
      o.put("bt", b)
    }
    return o.toString()
  }
}
