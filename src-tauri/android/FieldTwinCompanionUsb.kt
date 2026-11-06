package app.fieldtwin.teal

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

// USB cable link to the Field Twin Companion board, through an OTG adapter. The board's own USB port shows up as a
// serial line. Commands go down one per line and its GPS and road readings come back one per line, the same text as
// over Bluetooth. No ADB is involved; Android talks to the board directly.
object FieldTwinCompanionUsb {
  private const val ACTION = "app.fieldtwin.teal.COMPANION_USB_PERMISSION"
  private const val ESPRESSIF = 0x303A
  private val main = Handler(Looper.getMainLooper())
  private var conn: UsbDeviceConnection? = null
  private var dataIf: UsbInterface? = null
  private var commIf: UsbInterface? = null
  private var epIn: UsbEndpoint? = null
  private var epOut: UsbEndpoint? = null
  private var reader: Thread? = null
  private var receiverOn = false
  private var asked = false
  private var watching = false
  @Volatile var open = false

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      if (i.action != ACTION) return
      val um = c.getSystemService(Context.USB_SERVICE) as UsbManager
      val dev = findBoard(um) ?: return
      if (um.hasPermission(dev)) openBoard(um, dev)
    }
  }

  private fun findBoard(um: UsbManager): UsbDevice? = um.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF }

  // Looks for the board now and every few seconds while the companion switch is on, so plugging in later works too.
  fun watch(activity: Activity) {
    if (watching) return
    watching = true
    val tick = object : Runnable {
      override fun run() {
        if (!watching) return
        if (!open) tryOpen(activity)
        main.postDelayed(this, 3000)
      }
    }
    main.post(tick)
  }

  fun stopWatching() { watching = false; close() }

  private fun tryOpen(activity: Activity) {
    val um = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    val dev = findBoard(um) ?: run { asked = false; return }
    if (um.hasPermission(dev)) { openBoard(um, dev); return }
    if (asked) return
    asked = true
    if (!receiverOn) {
      ContextCompat.registerReceiver(activity.applicationContext, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
      receiverOn = true
    }
    val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
    val pi = PendingIntent.getBroadcast(activity, 0, Intent(ACTION).setPackage(activity.packageName), flags)
    um.requestPermission(dev, pi)
  }

  private fun openBoard(um: UsbManager, dev: UsbDevice) {
    if (open) return
    var dIf: UsbInterface? = null
    var cIf: UsbInterface? = null
    for (i in 0 until dev.interfaceCount) {
      val f = dev.getInterface(i)
      if (f.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && dIf == null) dIf = f
      if (f.interfaceClass == UsbConstants.USB_CLASS_COMM && cIf == null) cIf = f
    }
    val d = dIf ?: return
    var inEp: UsbEndpoint? = null
    var outEp: UsbEndpoint? = null
    for (e in 0 until d.endpointCount) {
      val ep = d.getEndpoint(e)
      if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
      if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
    }
    if (inEp == null || outEp == null) return
    val c = um.openDevice(dev) ?: return
    if (!c.claimInterface(d, true)) { c.close(); return }
    if (cIf != null) c.claimInterface(cIf, true)
    // Line settings, then data terminal ready on with request to send off, which keeps the board from resetting.
    val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
    val ifNum = cIf?.id ?: 0
    c.controlTransfer(0x21, 0x20, 0, ifNum, coding, coding.size, 1000)
    c.controlTransfer(0x21, 0x22, 0x01, ifNum, null, 0, 1000)
    conn = c; dataIf = d; commIf = cIf; epIn = inEp; epOut = outEp; open = true
    FieldTwinCompanion.usbState(true)
    val t = Thread {
      val buf = ByteArray(512)
      val line = StringBuilder()
      var lastPing = 0L
      while (open) {
        val n = try { c.bulkTransfer(inEp, buf, buf.size, 400) } catch (e: Exception) { -2 }
        if (n > 0) {
          for (k in 0 until n) {
            val ch = (buf[k].toInt() and 0xFF).toChar()
            if (ch == '\n' || ch == '\r') { if (line.isNotEmpty()) { FieldTwinCompanion.fromUsb(line.toString()); line.setLength(0) } }
            else if (line.length < 400) line.append(ch)
          }
        }
        val now = System.currentTimeMillis()
        if (now - lastPing > 3000) { lastPing = now; if (!write("PING")) break }
        if (um.deviceList.values.none { it.deviceId == dev.deviceId }) break
      }
      main.post { close() }
    }
    reader = t
    t.start()
  }

  @Synchronized
  fun write(text: String): Boolean {
    val c = conn ?: return false
    val ep = epOut ?: return false
    val b = (text + "\n").toByteArray(Charsets.UTF_8)
    return try { c.bulkTransfer(ep, b, b.size, 600) >= 0 } catch (e: Exception) { false }
  }

  fun close() {
    if (!open && conn == null) return
    open = false
    try { dataIf?.let { conn?.releaseInterface(it) }; commIf?.let { conn?.releaseInterface(it) } } catch (e: Exception) {}
    try { conn?.close() } catch (e: Exception) {}
    conn = null; dataIf = null; commIf = null; epIn = null; epOut = null
    asked = false
    FieldTwinCompanion.usbState(false)
  }
}
