package com.markdave.cameraradar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RadarActivity : ComponentActivity() {

    private lateinit var engine: RadarEngine
    private var server: RadarServer? = null
    private var port = 8080
    private var provider: ProcessCameraProvider? = null
    private var current = "rear"

    private val execRear = Executors.newSingleThreadExecutor()
    private val execFront = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tvLink: TextView
    private lateinit var tvAddr: TextView
    private lateinit var ivQr: ImageView
    private lateinit var ivRear: ImageView
    private lateinit var ivFront: ImageView
    private lateinit var tvRear: TextView
    private lateinit var tvFront: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvErr: TextView
    private lateinit var dim: View
    private var shownUrl = ""

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCameraProvider()
        else tvErr.text = "Camera permission is off. Allow it in Settings → Apps → Camera Radar → Permissions, then reopen this screen."
    }

    private val alternate = object : Runnable {
        override fun run() {
            if (engine.mode == "alternate" && !engine.settings.paused) {
                bindSingle(if (current == "rear") "front" else "rear", keepAlternate = true)
            }
            main.postDelayed(this, 4000)
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            refreshUi()
            main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        engine = RadarEngine(applicationContext)
        engine.onCamsChanged = { runOnUiThread { bindCameras() } }
        buildUi()

        worker.execute { engine.loadModel() }
        startServer()

        sched.scheduleAtFixedRate({
            val s = server
            if (s != null && s.clientCount > 0) {
                try {
                    s.broadcast(engine.frameJson())
                    engine.pendingSnaps().forEach { s.broadcast(it) }
                } catch (t: Throwable) { }
            }
        }, 500, 100, TimeUnit.MILLISECONDS)

        main.post(uiTick)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraProvider()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ------------------------------------------------------------------ server
    private fun startServer() {
        for (p in listOf(8080, 8081, 8090)) {
            try {
                val s = RadarServer(p, applicationContext, engine)
                s.start(0, false)          // 0 = no read timeout, keeps WebSockets open
                server = s
                port = p
                return
            } catch (e: Exception) { }
        }
        tvErr.text = "Couldn’t start the link for the screen phone. Close other apps and reopen Camera Radar."
    }

    // ------------------------------------------------------------------ cameras
    private fun startCameraProvider() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                provider = f.get()
                bindCameras()
            } catch (e: Exception) {
                tvErr.text = "The cameras couldn’t start (${e.javaClass.simpleName})."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analysisFor(cam: String): ImageAnalysis {
        val res = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        return ImageAnalysis.Builder()
            .setResolutionSelector(res)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { a -> a.setAnalyzer(if (cam == "rear") execRear else execFront) { proxy -> engine.onFrame(cam, proxy) } }
    }

    private fun has(sel: CameraSelector): Boolean = try { provider?.hasCamera(sel) == true } catch (e: Exception) { false }

    private fun bindCameras() {
        val p = provider ?: return
        main.removeCallbacks(alternate)
        p.unbindAll()
        engine.activate(emptyList())
        tvErr.text = ""
        val hasBack = has(CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = has(CameraSelector.DEFAULT_FRONT_CAMERA)
        val want = engine.settings.cams

        if (want == "both" && hasBack && hasFront) {
            if (tryConcurrent(p)) {
                engine.mode = "dual"
                engine.activate(listOf("rear", "front"))
                return
            }
            engine.mode = "alternate"
            bindSingle("rear", keepAlternate = true)
            main.postDelayed(alternate, 4000)
            return
        }
        val cam = when {
            want == "front" && hasFront -> "front"
            hasBack -> "rear"
            hasFront -> "front"
            else -> { tvErr.text = "No camera was found on this phone."; engine.mode = "none"; return }
        }
        engine.mode = "single"
        bindSingle(cam, keepAlternate = false)
    }

    /** Both cameras at the same time. Only some phones (Android 11+) allow this. */
    private fun tryConcurrent(p: ProcessCameraProvider): Boolean {
        return try {
            val combos = p.availableConcurrentCameraInfos
            for (combo in combos) {
                val back = CameraSelector.DEFAULT_BACK_CAMERA.filter(combo).firstOrNull() ?: continue
                val front = CameraSelector.DEFAULT_FRONT_CAMERA.filter(combo).firstOrNull() ?: continue
                val c1 = ConcurrentCamera.SingleCameraConfig(
                    back.cameraSelector, UseCaseGroup.Builder().addUseCase(analysisFor("rear")).build(), this
                )
                val c2 = ConcurrentCamera.SingleCameraConfig(
                    front.cameraSelector, UseCaseGroup.Builder().addUseCase(analysisFor("front")).build(), this
                )
                p.bindToLifecycle(listOf(c1, c2))
                return true
            }
            false
        } catch (e: Throwable) {
            try { p.unbindAll() } catch (e2: Throwable) { }
            false
        }
    }

    private fun bindSingle(cam: String, keepAlternate: Boolean) {
        val p = provider ?: return
        try {
            p.unbindAll()
            val sel = if (cam == "rear") CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            p.bindToLifecycle(this, sel, analysisFor(cam))
            current = cam
            engine.activate(listOf(cam))
        } catch (e: Exception) {
            if (!keepAlternate) tvErr.text = "The $cam camera couldn’t start (${e.javaClass.simpleName})."
        }
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val col = Ui.column(this)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(Ui.text(this, "Radar phone", 24f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvLink = Ui.text(this, "Waiting for the screen phone", 14f, Ui.MUTED).apply { gravity = Gravity.END }
        head.addView(tvLink, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)

        val pair = Ui.panel(this)
        tvAddr = Ui.text(this, "", 15f)
        pair.addView(tvAddr)
        ivQr = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(Ui.dp(this@RadarActivity, 6), Ui.dp(this@RadarActivity, 6), Ui.dp(this@RadarActivity, 6), Ui.dp(this@RadarActivity, 6)) }
        pair.addView(ivQr, LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 150)).apply { topMargin = Ui.dp(this@RadarActivity, 8) })
        col.addView(pair, Ui.lp(this))

        tvErr = Ui.text(this, "", 15f, Ui.ERR)
        col.addView(tvErr, Ui.lp(this, 6))

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (fr, ir, tr) = tile("Rear camera")
        val (ff, iff, tf) = tile("Front camera")
        ivRear = ir; tvRear = tr; ivFront = iff; tvFront = tf
        tiles.addView(fr, LinearLayout.LayoutParams(0, Ui.dp(this, 230), 1f).apply { marginEnd = Ui.dp(this@RadarActivity, 6) })
        tiles.addView(ff, LinearLayout.LayoutParams(0, Ui.dp(this, 230), 1f).apply { marginStart = Ui.dp(this@RadarActivity, 6) })
        col.addView(tiles, Ui.lp(this, 6))

        val info = Ui.panel(this)
        tvMode = Ui.text(this, "Starting cameras…")
        tvModel = Ui.text(this, "", 15f, Ui.MUTED)
        tvCount = Ui.text(this, "", 15f, Ui.MUTED)
        info.addView(tvMode); info.addView(tvModel); info.addView(tvCount)
        col.addView(info, Ui.lp(this))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "Dim screen", primary = true) { setDim(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@RadarActivity, 6) })
        row.addView(Ui.button(this, "Restart cameras") { bindCameras() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@RadarActivity, 6) })
        col.addView(row, Ui.lp(this))

        col.addView(Ui.text(this, "Stand this phone up so the back faces ahead. Keep this app open; if the phone locks, the cameras stop.", 14f, Ui.MUTED), Ui.lp(this))

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) }
        dim = TextView(this).apply {
            text = "Radar running. Tap to wake."
            setTextColor(0xFF3D4A4A.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { setDim(false) }
        }
        val root = FrameLayout(this)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun tile(label: String): Triple<FrameLayout, ImageView, TextView> {
        val f = FrameLayout(this).apply { background = Ui.box(this@RadarActivity, Color.BLACK, Ui.LINE, 10); clipToOutline = true }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val tv = Ui.text(this, "$label: off", 13f, Color.WHITE).apply { setShadowLayer(4f, 0f, 1f, Color.BLACK) }
        f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.dp(this@RadarActivity, 8); bottomMargin = Ui.dp(this@RadarActivity, 4)
        })
        return Triple(f, iv, tv)
    }

    private fun setDim(on: Boolean) {
        dim.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun refreshUi() {
        val n = server?.clientCount ?: 0
        tvLink.text = if (n == 0) "Waiting for the screen phone" else if (n == 1) "Screen phone linked" else "$n screens linked"

        for (cam in listOf("rear", "front")) {
            val live = cam in engine.active
            val st = engine.cams.getValue(cam)
            val iv = if (cam == "rear") ivRear else ivFront
            val tv = if (cam == "rear") tvRear else tvFront
            val name = if (cam == "rear") "Rear camera" else "Front camera"
            val state = when {
                live && engine.settings.paused -> "paused"
                live -> "live"
                engine.mode == "alternate" -> "waiting its turn"
                else -> "off"
            }
            tv.text = "$name: $state"
            if (live) st.tile?.let { iv.setImageBitmap(it) }
            iv.alpha = if (live) 1f else 0.35f
        }

        tvMode.text = when (engine.mode) {
            "dual" -> "Both cameras are live at the same time."
            "alternate" -> "This phone can’t run both cameras at once, so they take turns every 4 seconds."
            "single" -> "Only the ${engine.active.firstOrNull() ?: current} camera is running."
            else -> "Cameras off."
        }
        tvModel.text = when (engine.modelState) {
            "loading" -> "AI recognition loading…"
            "ready" -> "AI recognition ready: people, animals and vehicles."
            "failed" -> "AI recognition couldn’t load, so only plain motion is shown."
            else -> ""
        }
        val c = engine.contactCount()
        val shaky = engine.active.any { engine.cams.getValue(it).shaky }
        tvCount.text = when {
            shaky -> "The phone is moving. Hold it still."
            c == 0 -> "Nothing moving"
            c == 1 -> "1 contact tracked"
            else -> "$c contacts tracked"
        }

        val ip = hotspotIp()
        if (ip == null) {
            tvAddr.text = "Turn on this phone’s hotspot, then connect the screen phone to it."
            ivQr.visibility = View.GONE
            shownUrl = ""
        } else {
            val url = "http://$ip:$port/"
            if (url != shownUrl) {
                shownUrl = url
                tvAddr.text = "On the screen phone, open Camera Radar and tap “This phone is the screen”. It finds this phone by itself.\n\nNo app on the screen phone? Scan this or open $url in its browser."
                ivQr.setImageBitmap(qr(url))
                ivQr.visibility = View.VISIBLE
            }
        }
    }

    private var ipCache: String? = null
    private var ipCheckedAt = 0L
    private fun hotspotIp(): String? {
        val now = System.currentTimeMillis()
        if (now - ipCheckedAt < 3000) return ipCache
        ipCheckedAt = now
        ipCache = try {
            val list = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to (it.hostAddress ?: "") } }
            val apLike = list.firstOrNull { (n, _) ->
                n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n == "wlan1" || n.startsWith("wigig")
            } ?: list.firstOrNull { it.second == "192.168.43.1" }
            apLike?.second
        } catch (e: Exception) { null }
        return ipCache
    }

    private fun qr(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360)
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.RGB_565)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        sched.shutdownNow()
        try { provider?.unbindAll() } catch (e: Exception) { }
        try { server?.stop() } catch (e: Exception) { }
        execRear.shutdown(); execFront.shutdown(); worker.shutdown()
        super.onDestroy()
    }
}
