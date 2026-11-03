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

  // Pushes the page below the camera cutout and status bar, and above the gesture bar and the keyboard while it is open.
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
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode) v.setPadding(0, 0, 0, 0)
      else v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(root)
  }

  // Leaving the app while a route is running shrinks it to the floating window instead of hiding it.
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && FieldTwinState.prefs(this).getBoolean("autoPip", false)) {
      try {
        enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(3, 4)).build())
      } catch (e: Exception) {}
    }
  }

  // Android pauses the page when the app loses focus, which freezes the floating window on its last full size frame.
  // While floating, the page keeps drawing and running so it can shrink to the window and follow the route.
  private fun keepPageRunning() {
    val w = FieldTwinBridge.web ?: return
    try {
      w.onResume()
      w.resumeTimers()
      w.requestLayout()
      w.invalidate()
    } catch (e: Exception) {}
  }

  override fun onPause() {
    super.onPause()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode) {
      keepPageRunning()
      Handler(Looper.getMainLooper()).postDelayed({ keepPageRunning() }, 400)
    }
  }

  // Tells the page when the floating window opens or closes so it can show only the map and the next turn.
  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    val root = findViewById<android.view.View>(android.R.id.content)
    if (isInPictureInPictureMode) root.setPadding(0, 0, 0, 0) else ViewCompat.requestApplyInsets(root)
    keepPageRunning()
    FieldTwinBridge.web?.evaluateJavascript("window.ftPip && window.ftPip(" + isInPictureInPictureMode + ")", null)
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
    FieldTwinBridge.web = webView
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
    if (requestCode == FieldTwinCompanion.PERM_CODE) FieldTwinCompanion.afterPermission(this)
  }
}
