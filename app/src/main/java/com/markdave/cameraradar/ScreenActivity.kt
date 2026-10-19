package com.markdave.cameraradar

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class ScreenActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var msg: TextView
    private val main = Handler(Looper.getMainLooper())
    private var failed = false
    private var address = ""
    private val retry = Runnable { load() }

    private val prefs by lazy { getSharedPreferences("radar", Context.MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        web = WebView(this).apply {
            setBackgroundColor(Ui.BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showError()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!failed) overlay.visibility = View.GONE
                }
            }
        }

        overlay = Ui.column(this, 24).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
        }
        msg = Ui.text(this, "Looking for the radar phone…", 18f)
        overlay.addView(msg)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "Try again", primary = true) { load() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@ScreenActivity, 6) })
        row.addView(Ui.button(this, "Enter address") { askAddress() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@ScreenActivity, 6) })
        overlay.addView(row, Ui.lp(this, 20))

        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        load()
    }

    /** Keep traffic on Wi-Fi even though the hotspot may have no internet. */
    private fun bindToWifi() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            cm.bindProcessToNetwork(wifi)
        } catch (e: Exception) { }
    }

    /** The hotspot phone is this phone's Wi-Fi gateway. */
    private fun gatewayAddress(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val g = wm.dhcpInfo?.gateway ?: 0
            if (g != 0) "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g ushr 24) and 0xff}:8080"
            else "192.168.43.1:8080"
        } catch (e: Exception) { "192.168.43.1:8080" }
    }

    private fun load() {
        main.removeCallbacks(retry)
        bindToWifi()
        address = prefs.getString("manual", null)?.takeIf { it.isNotBlank() } ?: gatewayAddress()
        failed = false
        msg.text = "Looking for the radar phone at $address…"
        overlay.visibility = View.VISIBLE
        web.loadUrl("http://$address/")
    }

    private fun showError() {
        failed = true
        overlay.visibility = View.VISIBLE
        msg.text = "Can’t reach the radar phone at $address.\n\n" +
            "Check that the radar phone has Camera Radar open as the radar, and that this phone is connected to its hotspot.\n\n" +
            "Trying again in a few seconds."
        main.removeCallbacks(retry)
        main.postDelayed(retry, 4000)
    }

    private fun askAddress() {
        main.removeCallbacks(retry)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.43.1:8080"
            setText(prefs.getString("manual", "") ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Radar phone address")
            .setMessage("Type the address shown on the radar phone, or leave it empty to find it automatically.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val v = input.text.toString().trim().removePrefix("http://").trimEnd('/')
                val withPort = if (v.isNotEmpty() && !v.contains(":")) "$v:8080" else v
                prefs.edit().putString("manual", withPort).apply()
                load()
            }
            .setNegativeButton("Cancel") { _, _ -> if (failed) main.postDelayed(retry, 4000) }
            .show()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
        } catch (e: Exception) { }
        web.destroy()
        super.onDestroy()
    }
}
