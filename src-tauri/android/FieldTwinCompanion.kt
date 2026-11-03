package app.fieldtwin.teal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

// Bluetooth link to the Field Twin Companion dashboard screen. Finds the board named FT Companion, keeps the link,
// sends it route and turn commands one at a time, and hands its GPS and road readings to the page.
object FieldTwinCompanion {
  val SERVICE: UUID = UUID.fromString("b2f0a001-6c1e-4f2a-9d4b-667477000001")
  val NAV: UUID = UUID.fromString("b2f0a001-6c1e-4f2a-9d4b-667477000002")
  val POS: UUID = UUID.fromString("b2f0a001-6c1e-4f2a-9d4b-667477000003")
  val ROAD: UUID = UUID.fromString("b2f0a001-6c1e-4f2a-9d4b-667477000004")
  val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  const val PERM_CODE = 4419

  private val main = Handler(Looper.getMainLooper())
  private var ctx: Context? = null
  private var gatt: BluetoothGatt? = null
  private var nav: BluetoothGattCharacteristic? = null
  private val queue = ArrayDeque<ByteArray>()
  private val descQueue = ArrayDeque<BluetoothGattDescriptor>()
  private var busy = false
  private var wanted = false
  private var scanning = false
  @Volatile var state = "off"

  private fun toPage(kind: String, text: String) {
    val js = "window.ftCompanionIn && window.ftCompanionIn(" + JSONObject.quote(kind) + "," + JSONObject.quote(text) + ")"
    main.post { try { FieldTwinBridge.web?.evaluateJavascript(js, null) } catch (e: Exception) {} }
  }
  private fun announce(s: String) { state = s; toPage("state", s) }

  fun permsNeeded(c: Context): Array<String> {
    val need = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
      if (ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN)
      if (ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT)
    } else if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      need.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return need.toTypedArray()
  }

  fun start(activity: Activity) {
    ctx = activity.applicationContext
    wanted = true
    if (gatt != null) { announce(state); return }
    val need = permsNeeded(activity)
    if (need.isNotEmpty()) { announce("asking"); ActivityCompat.requestPermissions(activity, need, PERM_CODE); return }
    main.post { scan() }
  }

  fun afterPermission(activity: Activity) {
    if (!wanted) return
    if (permsNeeded(activity).isEmpty()) main.post { scan() } else announce("no permission")
  }

  @SuppressLint("MissingPermission")
  fun stop() {
    wanted = false
    try { stopScan() } catch (e: Exception) {}
    try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}
    gatt = null; nav = null; queue.clear(); busy = false
    announce("off")
  }

  private fun adapter(): BluetoothAdapter? = (ctx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private val scanCb = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      if (!scanning) return
      stopScan()
      connect(result.device)
    }
    override fun onScanFailed(errorCode: Int) { scanning = false; announce("scan failed"); retryLater() }
  }

  @SuppressLint("MissingPermission")
  private fun scan() {
    if (!wanted || gatt != null || scanning) return
    val a = adapter()
    if (a == null || !a.isEnabled) { announce("bluetooth off"); retryLater(); return }
    val sc = a.bluetoothLeScanner ?: run { announce("bluetooth off"); retryLater(); return }
    scanning = true
    announce("searching")
    val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    try { sc.startScan(filters, settings, scanCb) } catch (e: Exception) { scanning = false; announce("scan failed"); retryLater(); return }
    main.postDelayed({ if (scanning) { stopScan(); announce("not found"); retryLater() } }, 15000)
  }

  @SuppressLint("MissingPermission")
  private fun stopScan() {
    if (!scanning) return
    scanning = false
    try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) {}
  }

  private fun retryLater() { main.postDelayed({ if (wanted && gatt == null) scan() }, 10000) }

  @SuppressLint("MissingPermission")
  private fun connect(d: BluetoothDevice) {
    announce("connecting")
    gatt = if (Build.VERSION.SDK_INT >= 23) d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE) else d.connectGatt(ctx, false, gattCb)
  }

  private val gattCb = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        main.post { try { g.requestMtu(247) } catch (e: Exception) { g.discoverServices() } }
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        main.post {
          try { g.close() } catch (e: Exception) {}
          gatt = null; nav = null; queue.clear(); descQueue.clear(); busy = false
          announce("lost")
          if (wanted) main.postDelayed({ scan() }, 3000)
        }
      }
    }

    @SuppressLint("MissingPermission")
    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { main.post { g.discoverServices() } }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      main.post {
        val svc = g.getService(SERVICE)
        if (svc == null) { announce("wrong device"); g.disconnect(); return@post }
        nav = svc.getCharacteristic(NAV)
        for (u in listOf(POS, ROAD)) {
          val ch = svc.getCharacteristic(u) ?: continue
          g.setCharacteristicNotification(ch, true)
          val d = ch.getDescriptor(CCCD) ?: continue
          descQueue.add(d)
        }
        nextDescriptor(g)
      }
    }

    @SuppressLint("MissingPermission")
    private fun nextDescriptor(g: BluetoothGatt) {
      val d = descQueue.removeFirstOrNull()
      if (d == null) { announce("linked"); pump(); return }
      @Suppress("DEPRECATION")
      d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
      @Suppress("DEPRECATION")
      if (!g.writeDescriptor(d)) nextDescriptor(g)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { main.post { nextDescriptor(g) } }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { main.post { busy = false; pump() } }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
      @Suppress("DEPRECATION")
      val v = c.value ?: return
      toPage(if (c.uuid == ROAD) "road" else "pos", String(v, Charsets.UTF_8))
    }
  }

  // Commands go out one at a time; the next leaves when the board confirms the last one.
  fun send(cmd: String) {
    val b = cmd.toByteArray(Charsets.UTF_8)
    main.post {
      if (queue.size > 400) queue.clear()
      queue.add(if (b.size > 240) b.copyOf(240) else b)
      pump()
    }
  }

  @SuppressLint("MissingPermission")
  private fun pump() {
    val g = gatt ?: return
    val ch = nav ?: return
    if (busy || state != "linked") return
    val next = queue.removeFirstOrNull() ?: return
    busy = true
    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    @Suppress("DEPRECATION")
    ch.value = next
    @Suppress("DEPRECATION")
    val ok = try { g.writeCharacteristic(ch) } catch (e: Exception) { false }
    if (!ok) { busy = false; main.postDelayed({ queue.addFirst(next); pump() }, 200) }
  }
}
