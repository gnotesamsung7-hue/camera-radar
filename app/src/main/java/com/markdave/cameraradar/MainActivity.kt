package com.markdave.cameraradar

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

object Ui {
    const val BG = 0xFF0D191C.toInt()
    const val PANEL = 0xFF13252A.toInt()
    const val INK = 0xFFD9E7E4.toInt()
    const val MUTED = 0xFF8AA5A2.toInt()
    const val ACCENT = 0xFFFFB547.toInt()
    const val LINE = 0xFF24413F.toInt()
    const val ERR = 0xFFFFB08A.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String, size: Float = 16f, color: Int = INK, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        }

    fun box(ctx: Context, fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, radius).toFloat()
            setColor(fill)
            setStroke(dp(ctx, 1), stroke)
        }

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(if (primary) 0xFF1B1000.toInt() else INK)
            background = box(ctx, if (primary) ACCENT else PANEL, if (primary) ACCENT else LINE, 10)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    fun panel(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        background = box(ctx, PANEL, LINE, 14)
    }

    fun column(ctx: Context, pad: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, pad), dp(ctx, pad), dp(ctx, pad), dp(ctx, pad))
    }

    fun lp(ctx: Context, top: Int = 12): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, top) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val col = Ui.column(this, 24).apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(Ui.text(this, "Camera Radar", 36f, bold = true))
        col.addView(
            Ui.text(
                this,
                "Uses two phones. The radar phone turns on its hotspot and watches with both cameras. " +
                    "The screen phone joins that hotspot and shows the radar. No internet needed.",
                16f, Ui.MUTED
            ), Ui.lp(this, 8)
        )
        col.addView(roleCard("This phone is the radar", "Uses the rear and front cameras. Turn on this phone’s hotspot.") {
            startActivity(Intent(this, RadarActivity::class.java))
        }, Ui.lp(this, 24))
        col.addView(roleCard("This phone is the screen", "Join the radar phone’s hotspot first. Shows the radar and alerts.") {
            startActivity(Intent(this, ScreenActivity::class.java))
        }, Ui.lp(this, 12))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(scroll)
    }

    private fun roleCard(title: String, sub: String, onClick: () -> Unit): LinearLayout =
        Ui.panel(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(Ui.text(this@MainActivity, title, 22f, bold = true))
            addView(Ui.text(this@MainActivity, sub, 15f, Ui.MUTED))
        }
}
