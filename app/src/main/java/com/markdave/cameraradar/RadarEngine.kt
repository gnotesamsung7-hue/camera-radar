package com.markdave.cameraradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Base64
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns camera frames into radar contacts.
 * Rear camera looks "ahead" (bearing 0°), front camera looks "behind" (bearing 180°).
 */
class RadarEngine(private val ctx: Context) {

    companion object {
        const val W = 60
        const val H = 80
        const val N = W * H
        val ANIMALS = setOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
        val VEHICLES = setOf("bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat")
        val COLORS = mapOf(
            "person" to 0xFFFFB547.toInt(),
            "animal" to 0xFF7EE08A.toInt(),
            "vehicle" to 0xFF6CC7FF.toInt(),
            "motion" to 0xFFC9D6D3.toInt()
        )

        fun classify(c: String): String? = when {
            c == "person" -> "person"
            c in ANIMALS -> "animal"
            c in VEHICLES -> "vehicle"
            else -> null
        }

        fun angNorm(a: Double): Double = ((a % 360.0) + 540.0) % 360.0 - 180.0
        private fun r1(v: Double) = Math.round(v * 10.0) / 10.0
        private fun r3(v: Double) = Math.round(v * 1000.0) / 1000.0
        private fun r4(v: Double) = Math.round(v * 10000.0) / 10000.0
    }

    class Settings {
        @Volatile var sens = 0.55
        @Volatile var movingOnly = true
        @Volatile var cams = "both"
        @Volatile var peek = false
        @Volatile var paused = false
    }

    class Blob(val area: Int, val box: FloatArray)
    class Det(val type: String, val label: String, val box: FloatArray)
    class Obs(val cam: String, val bearing: Double, val range: Double, val size: Double,
              val type: String, val label: String, val moving: Boolean)

    class Contact(
        val id: Int, val cam: String, var bearing: Double, var range: Double, var size: Double,
        var type: String, var label: String, var trend: Double, var seen: Long, var lastMove: Long
    )

    class CamState {
        val px = IntArray(N)
        val gray = FloatArray(N)
        val mask = IntArray(N)
        val mask2 = IntArray(N)
        val seen = IntArray(N)
        val stack = IntArray(N)
        var bg: FloatArray? = null
        var warm = 0
        @Volatile var blobs: List<Blob> = emptyList()
        @Volatile var dets: List<Det> = emptyList()
        @Volatile var detTime = 0L
        var lastDetAttempt = 0L
        var lastFrame = 0L
        var lastTile = 0L
        var lastSnap = 0L
        @Volatile var shaky = false
        @Volatile var fov = 50.0
        @Volatile var tile: Bitmap? = null
        @Volatile var snap: String? = null
        @Volatile var snapSent = true

        fun reset() {
            bg = null; warm = 0; blobs = emptyList(); dets = emptyList(); shaky = false
        }
    }

    val settings = Settings()
    val cams = mapOf("rear" to CamState(), "front" to CamState())
    @Volatile var mode = "none"
    @Volatile var active: List<String> = emptyList()
        private set
    @Volatile var modelState = "off"
    var onCamsChanged: (() -> Unit)? = null

    private var detector: ObjectDetector? = null
    private val detLock = Any()
    private val contacts = ArrayList<Contact>()
    private var nextId = 0

    fun activate(list: List<String>) {
        list.forEach { cams[it]?.reset() }
        active = list
    }

    fun loadModel() {
        modelState = "loading"
        try {
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(8)
                .setScoreThreshold(0.45f)
                .build()
            detector = ObjectDetector.createFromOptions(ctx, opts)
            modelState = "ready"
        } catch (t: Throwable) {
            modelState = "failed"
        }
    }

    fun contactCount(): Int = synchronized(contacts) { contacts.size }

    // ------------------------------------------------------------------ frames
    fun onFrame(cam: String, proxy: ImageProxy) {
        try {
            if (settings.paused || cam !in active) return
            val st = cams[cam] ?: return
            val now = SystemClock.uptimeMillis()
            if (now - st.lastFrame < 90) return
            st.lastFrame = now

            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot != 0) {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            } else raw
            st.fov = if (upright.height > upright.width) 50.0 else 66.0

            val small = Bitmap.createScaledBitmap(upright, W, H, true)
            analyze(st, small)

            val det = detector
            if (det != null && now - st.lastDetAttempt > 300) {
                st.lastDetAttempt = now
                val dw = 320
                val dh = (320f * upright.height / upright.width).toInt().coerceAtLeast(1)
                val dBmp = Bitmap.createScaledBitmap(upright, dw, dh, true)
                val result = synchronized(detLock) { det.detect(BitmapImageBuilder(dBmp).build()) }
                st.dets = result.detections().mapNotNull { d ->
                    val c = d.categories().firstOrNull() ?: return@mapNotNull null
                    val type = classify(c.categoryName()) ?: return@mapNotNull null
                    val b = d.boundingBox()
                    Det(type, c.categoryName(), floatArrayOf(b.left / dw * W, b.top / dh * H, b.right / dw * W, b.bottom / dh * H))
                }
                st.detTime = now
            }

            track(cam, observe(cam, st, now), now)

            if (now - st.lastTile > 250) {
                st.lastTile = now
                st.tile = makeTile(upright, st, now)
            }
            if (settings.peek && now - st.lastSnap > 1400) {
                st.lastSnap = now
                st.snap = jpegBase64(upright, 200)
                st.snapSent = false
            }
        } catch (t: Throwable) {
            // skip a bad frame
        } finally {
            proxy.close()
        }
    }

    private fun analyze(st: CamState, small: Bitmap) {
        small.getPixels(st.px, 0, W, 0, 0, W, H)
        for (i in 0 until N) {
            val p = st.px[i]
            st.gray[i] = ((p shr 16) and 0xff) * 0.299f + ((p shr 8) and 0xff) * 0.587f + (p and 0xff) * 0.114f
        }
        val bg = st.bg
        if (bg == null || st.warm < 4) {
            st.warm++
            st.bg = st.gray.copyOf()
            st.blobs = emptyList()
            return
        }
        val sens = settings.sens.toFloat()
        val thr = 10f + (1f - sens) * 38f
        var moving = 0
        for (i in 0 until N) {
            val m = abs(st.gray[i] - bg[i]) > thr
            st.mask[i] = if (m) 1 else 0
            if (m) moving++
            bg[i] += (st.gray[i] - bg[i]) * (if (m) 0.03f else 0.12f)
        }
        if (moving.toFloat() / N > 0.35f) {       // whole picture changed: the phone itself moved
            System.arraycopy(st.gray, 0, bg, 0, N)
            st.shaky = true
            st.blobs = emptyList()
            return
        }
        st.shaky = false

        java.util.Arrays.fill(st.mask2, 0)
        for (y in 0 until H) for (x in 0 until W) {
            if (st.mask[y * W + x] == 0) continue
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until W && ny in 0 until H) st.mask2[ny * W + nx] = 1
            }
        }

        java.util.Arrays.fill(st.seen, 0)
        val blobs = ArrayList<Blob>()
        val minArea = 8f + (1f - sens) * 22f
        for (s0 in 0 until N) {
            if (st.mask2[s0] == 0 || st.seen[s0] != 0) continue
            var sp = 0
            var area = 0
            var minx = W; var maxx = 0; var miny = H; var maxy = 0
            st.stack[sp++] = s0
            st.seen[s0] = 1
            while (sp > 0) {
                val p = st.stack[--sp]
                val x = p % W
                val y = p / W
                area++
                if (x < minx) minx = x
                if (x > maxx) maxx = x
                if (y < miny) miny = y
                if (y > maxy) maxy = y
                if (x > 0 && st.mask2[p - 1] != 0 && st.seen[p - 1] == 0) { st.seen[p - 1] = 1; st.stack[sp++] = p - 1 }
                if (x < W - 1 && st.mask2[p + 1] != 0 && st.seen[p + 1] == 0) { st.seen[p + 1] = 1; st.stack[sp++] = p + 1 }
                if (y > 0 && st.mask2[p - W] != 0 && st.seen[p - W] == 0) { st.seen[p - W] = 1; st.stack[sp++] = p - W }
                if (y < H - 1 && st.mask2[p + W] != 0 && st.seen[p + W] == 0) { st.seen[p + W] = 1; st.stack[sp++] = p + W }
            }
            if (area >= minArea) {
                blobs.add(Blob(area, floatArrayOf(minx.toFloat(), miny.toFloat(), maxx.toFloat(), maxy.toFloat())))
            }
        }
        blobs.sortByDescending { it.area }
        st.blobs = blobs.take(6)
    }

    private fun boxArea(b: FloatArray) = (b[2] - b[0] + 1f) * (b[3] - b[1] + 1f)
    private fun overlap(a: FloatArray, b: FloatArray): Float {
        val ix = maxOf(0f, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0f, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        return ix * iy
    }

    private fun mkObs(cam: String, box: FloatArray, type: String, label: String, moving: Boolean, fov: Double): Obs {
        val nx = (((box[0] + box[2]) / 2f + 0.5f) / W).toDouble().coerceIn(0.0, 1.0)
        val frac = (((box[2] - box[0] + 1f) * (box[3] - box[1] + 1f)) / N).toDouble().coerceIn(0.0, 1.0)
        val range = (1.0 - sqrt(frac) * 1.6).coerceIn(0.08, 0.95)
        // front frames aren't mirrored: the image's left edge is the phone's right-hand side when looking back
        val bearing = if (cam == "rear") (nx - 0.5) * fov else 180.0 + (nx - 0.5) * fov
        return Obs(cam, bearing, range, frac, type, label, moving)
    }

    private fun observe(cam: String, st: CamState, now: Long): List<Obs> {
        val blobs = st.blobs
        val dets = if (now - st.detTime < 1000) st.dets else emptyList()
        val obs = ArrayList<Obs>()
        val used = HashSet<Int>()
        for (d in dets) {
            var moving = false
            blobs.forEachIndexed { i, b ->
                if (overlap(b.box, d.box) > 0.25f * boxArea(b.box)) { moving = true; used.add(i) }
            }
            if (settings.movingOnly && !moving) continue
            obs.add(mkObs(cam, d.box, d.type, d.label, moving, st.fov))
        }
        blobs.forEachIndexed { i, b ->
            if (i !in used) obs.add(mkObs(cam, b.box, "motion", "motion", true, st.fov))
        }
        return obs
    }

    private fun track(cam: String, obsIn: List<Obs>, now: Long) = synchronized(contacts) {
        val used = HashSet<Contact>()
        val obsAll = obsIn.sortedBy { if (it.type == "motion") 1 else 0 }
        for (o in obsAll) {
            var best: Contact? = null
            var bd = Double.MAX_VALUE
            for (c in contacts) {
                if (c in used || c.cam != cam) continue
                var d = abs(angNorm(c.bearing - o.bearing)) / 12.0 + abs(c.range - o.range) / 0.3
                if (c.type != "motion" && o.type != "motion" && c.type != o.type) d += 1.0
                if (d < bd) { bd = d; best = c }
            }
            val b = best
            if (b != null && bd < 1.5) {
                b.bearing += angNorm(o.bearing - b.bearing) * 0.5
                val nr = b.range + (o.range - b.range) * 0.4
                b.trend = b.trend * 0.8 + (nr - b.range) * 0.2
                b.range = nr
                b.size = o.size
                b.seen = now
                if (o.type != "motion") { b.type = o.type; b.label = o.label }
                if (o.moving) b.lastMove = now
                used.add(b)
            } else {
                val c = Contact(++nextId, cam, o.bearing, o.range, o.size, o.type, o.label, 0.0, now,
                    if (o.moving) now else -1_000_000L)
                contacts.add(c)
                used.add(c)
            }
        }
        contacts.removeAll { it.cam == cam && now - it.seen > 1200 }
    }

    // ------------------------------------------------------------------ previews
    private fun makeTile(upright: Bitmap, st: CamState, now: Long): Bitmap {
        val tw = 240
        val th = (240f * upright.height / upright.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(upright, tw, th, true)
        val tile = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(tile)
        val sx = tw.toFloat() / W
        val sy = th.toFloat() / H
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        p.color = 0xCCC9D6D3.toInt()
        for (b in st.blobs) c.drawRect(b.box[0] * sx, b.box[1] * sy, (b.box[2] + 1) * sx, (b.box[3] + 1) * sy, p)
        if (now - st.detTime < 1000) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; isFakeBoldText = true }
            for (d in st.dets) {
                val col = COLORS[d.type] ?: 0xFFFFFFFF.toInt()
                p.color = col; tp.color = col
                c.drawRect(d.box[0] * sx, d.box[1] * sy, d.box[2] * sx, d.box[3] * sy, p)
                c.drawText(d.label.replaceFirstChar { it.uppercase() }, d.box[0] * sx + 4, maxOf(18f, d.box[1] * sy - 4), tp)
            }
        }
        return tile
    }

    private fun jpegBase64(bmp: Bitmap, width: Int): String {
        val h = (width.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(bmp, width, h, true)
        val out = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.JPEG, 55, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ messages
    private fun statusJson(): JSONObject {
        val rear = cams.getValue("rear")
        val front = cams.getValue("front")
        return JSONObject()
            .put("mode", mode)
            .put("active", JSONArray(active))
            .put("model", modelState)
            .put("started", mode != "none")
            .put("paused", settings.paused)
            .put("shaky", JSONObject().put("rear", rear.shaky).put("front", front.shaky))
            .put("fov", JSONObject().put("rear", rear.fov).put("front", front.fov))
    }

    private fun settingsJson(): JSONObject = JSONObject()
        .put("sens", settings.sens).put("movingOnly", settings.movingOnly).put("cams", settings.cams)
        .put("peek", settings.peek).put("paused", settings.paused)

    fun helloJson(): String =
        JSONObject().put("t", "hello").put("status", statusJson()).put("settings", settingsJson()).toString()

    fun frameJson(): String {
        val now = SystemClock.uptimeMillis()
        val arr = JSONArray()
        synchronized(contacts) {
            contacts.removeAll { now - it.seen > (if (it.cam in active) 1200 else 6000) }
            for (c in contacts) {
                arr.put(
                    JSONObject()
                        .put("id", c.id).put("cam", c.cam)
                        .put("b", r1(c.bearing)).put("r", r3(c.range)).put("s", r4(c.size))
                        .put("type", c.type).put("label", c.label)
                        .put("moving", now - c.lastMove < 1500)
                        .put("trend", r4(c.trend))
                        .put("live", c.cam in active)
                )
            }
        }
        return JSONObject().put("t", "frame").put("status", statusJson()).put("contacts", arr).toString()
    }

    fun pendingSnaps(): List<String> {
        val out = ArrayList<String>()
        for ((cam, st) in cams) {
            val s = st.snap
            if (s != null && !st.snapSent && settings.peek) {
                st.snapSent = true
                out.add(JSONObject().put("t", "snap").put("cam", cam).put("data", s).toString())
            }
        }
        return out
    }

    fun applySettings(j: JSONObject) {
        if (j.optString("t") != "settings") return
        val prevCams = settings.cams
        val prevPaused = settings.paused
        if (j.has("sens")) settings.sens = j.optDouble("sens", settings.sens).coerceIn(0.0, 1.0)
        if (j.has("movingOnly")) settings.movingOnly = j.optBoolean("movingOnly", settings.movingOnly)
        if (j.has("peek")) settings.peek = j.optBoolean("peek", settings.peek)
        if (j.has("paused")) settings.paused = j.optBoolean("paused", settings.paused)
        if (j.has("cams")) {
            val c = j.optString("cams", settings.cams)
            if (c == "both" || c == "rear" || c == "front") settings.cams = c
        }
        if (prevPaused && !settings.paused) cams.values.forEach { it.reset() }
        if (settings.cams != prevCams) onCamsChanged?.invoke()
    }
}
