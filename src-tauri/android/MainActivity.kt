package app.fieldtwin.teal

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.CookieManager
import android.graphics.Color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

// Field Twin main screen: hands the page a FieldTwinNative object and passes odometer prompt taps through.
class MainActivity : TauriActivity() {
  private var reloadedForBridge = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    keepClearOfStatusBar()
    FieldTwinBridge.takeIntent(this, intent)
    if (FieldTwinState.prefs(this).getBoolean("trackingOn", true)) FieldTwinBridge.askAndStart(this)
  }

  // Pushes the page below the camera cutout and status bar, and above the gesture bar.
  private fun keepClearOfStatusBar() {
    window.statusBarColor = Color.WHITE
    window.navigationBarColor = Color.WHITE
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = true
      isAppearanceLightNavigationBars = true
    }
    val root = findViewById<android.view.View>(android.R.id.content)
    root.setBackgroundColor(Color.WHITE)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(root)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    FieldTwinBridge.takeIntent(this, intent)
  }

  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    // Inventory and Roads load inside this app from their own web addresses. Android blocks their saved
    // logins by default when they sit inside another page, so allow it for this app's own screen.
    try {
      CookieManager.getInstance().setAcceptCookie(true)
      CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
      webView.settings.domStorageEnabled = true
      webView.settings.databaseEnabled = true
    } catch (e: Exception) {}
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
