package app.fieldtwin.teal

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

// Field Twin main screen: hands the page a FieldTwinNative object and passes odometer prompt taps through.
class MainActivity : TauriActivity() {
  private var reloadedForBridge = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    FieldTwinBridge.takeIntent(this, intent)
    if (FieldTwinState.prefs(this).getBoolean("trackingOn", true)) FieldTwinBridge.askAndStart(this)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    FieldTwinBridge.takeIntent(this, intent)
  }

  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    webView.addJavascriptInterface(FieldTwinBridge(this), "FieldTwinNative")
    Handler(Looper.getMainLooper()).postDelayed({
      if (!FieldTwinBridge.helloSeen && !reloadedForBridge) {
        reloadedForBridge = true
        webView.reload()
      }
    }, 5000)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == FieldTwinBridge.PERM_CODE) FieldTwinBridge.startIfAllowed(this)
  }
}
