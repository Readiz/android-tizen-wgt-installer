// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.*

/** Offline, illustrated guide. All artwork is original vector geometry, not source photographs. */
class SetupGuideActivity : Activity() {
    private val ink = Color.rgb(28, 33, 38)
    private val accent = Color.rgb(16, 108, 87)
    private lateinit var phone: TextView
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setBackgroundColor(Color.WHITE) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(16), dp(4)) }
        header.addView(ImageButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            setImageResource(R.drawable.ic_arrow_back)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = getString(R.string.guide_done)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(R.string.guide_title, 21, true), LinearLayout.LayoutParams(0, -2, 1f)); root.addView(header)
        val body = column().apply { setPadding(dp(20), 0, dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { id = android.R.id.content; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        body.addView(label(R.string.guide_intro))
        body.addView(label(R.string.guide_diagram_note, 12))
        val apps = step(body, 1, R.string.setup_apps_title)
        apps.addView(GuideDiagram(this, 1).apply { contentDescription = getString(R.string.setup_apps_path) })
        apps.addView(label(R.string.setup_apps_path, 18, true)); apps.addView(label(R.string.setup_apps_body))
        val numbers = step(body, 2, R.string.setup_code_title)
        numbers.addView(GuideDiagram(this, 2).apply { contentDescription = getString(R.string.setup_code_keys) })
        numbers.addView(label(R.string.setup_code_body))
        val host = step(body, 3, R.string.setup_host_title)
        host.addView(GuideDiagram(this, 3).apply { contentDescription = getString(R.string.setup_host_body) })
        host.addView(label(R.string.setup_host_field, 15, true))
        phone = label(R.string.setup_no_wifi, 24, true).apply {
            typeface = Typeface.MONOSPACE; setTextColor(accent); setTextIsSelectable(true); maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(12, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        host.addView(phone); host.addView(label(R.string.setup_host_confirm)); host.addView(label(R.string.setup_keep_host, 13))
        val restart = step(body, 4, R.string.setup_restart_title)
        restart.addView(label(R.string.setup_restart_body)); restart.addView(label(R.string.setup_restart_help, 13))
        body.addView(Button(this).apply { setText(R.string.guide_done); isAllCaps = false; setOnClickListener { finish() } })
        body.addView(label(R.string.guide_sources, 13, true))
        link(body, R.string.guide_samsung, "https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html")
    }
    override fun onResume() {
        super.onResume()
        val localIp = runCatching { WifiLan(this).localIp }.getOrNull()
        phone.maxLines = if (localIp == null) 2 else 1
        phone.text = localIp ?: getString(R.string.setup_no_wifi)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(res: Int, size: Int = 15, bold: Boolean = false) = TextView(this).apply {
        setText(res); textSize = size.toFloat(); setTextColor(ink); setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun step(parent: LinearLayout, n: Int, title: Int): LinearLayout = column().also {
        it.setPadding(dp(16), dp(12), dp(16), dp(16))
        it.background = GradientDrawable().apply { setColor(Color.rgb(246, 248, 247)); cornerRadius = dp(12).toFloat() }
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        it.addView(label(title, 18, true).apply {
            text = getString(R.string.guide_step, n, getString(title))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
    }
    private fun link(parent: LinearLayout, label: Int, url: String) {
        parent.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(label); isAllCaps = false; textSize = 12f; setTextColor(accent)
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        })
    }
}

/** Responsive, schematic TV / remote / menu panels in a fixed logical coordinate space. */
private class GuideDiagram @JvmOverloads constructor(context: Context, private val scene: Int = 1) : View(context) {
    private val ink = Color.rgb(28, 45, 42)
    private val green = Color.rgb(16, 108, 87)
    private val mint = Color.rgb(213, 239, 225)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.60f).toInt())
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.save(); c.scale(width / 320f, width / 320f)
        fun box(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 8f) {
            paint.color = color; paint.style = Paint.Style.FILL; c.drawRoundRect(x, y, x + w, y + h, radius, radius, paint)
        }
        fun word(value: String, x: Float, y: Float, size: Float = 14f, color: Int = ink) {
            paint.color = color; paint.textSize = size; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            c.drawText(value, x, y, paint)
        }
        when (scene) {
            1 -> {
                box(12f, 12f, 296f, 148f, ink); box(20f, 20f, 280f, 132f, Color.WHITE)
                word("Home", 34f, 47f); word("›", 89f, 47f); word("Apps", 110f, 47f, color = green)
                for (i in 0..3) box(34f + i * 66, 62f, 54f, 42f, if (i == 0) mint else Color.rgb(232, 236, 235))
                word("Apps", 40f, 88f, 12f, green)
                box(132f, 115f, 150f, 26f, mint); word("App Settings", 145f, 133f, 13f, green)
                box(146f, 160f, 28f, 10f, ink); box(112f, 170f, 96f, 5f, ink)
            }
            2 -> {
                box(12f, 5f, 76f, 180f, ink, 28f)
                box(27f, 23f, 46f, 30f, mint, 15f); word("123", 36f, 44f, 17f, green)
                paint.color = Color.rgb(80, 99, 92); c.drawCircle(50f, 100f, 25f, paint)
                paint.color = Color.WHITE; c.drawCircle(50f, 100f, 10f, paint)
                word("‹", 29f, 105f, 16f, Color.WHITE); word("›", 65f, 105f, 16f, Color.WHITE)
                word("→", 100f, 57f, 26f, green)
                box(138f, 25f, 172f, 126f, Color.WHITE)
                word("1 → 2 → 3 → 4 → 5", 147f, 51f, 14f, green)
                for (i in 0..9) {
                    val x = 149f + (i % 5) * 30; val y = 67f + (i / 5) * 36
                    box(x, y, 25f, 28f, if (i < 5) mint else Color.rgb(236, 239, 238), 4f)
                    word(((i + 1) % 10).toString(), x + 8, y + 19, 14f)
                }
            }
            3 -> {
                box(12f, 10f, 296f, 167f, Color.WHITE)
                word("Developer Mode", 30f, 43f, 17f)
                box(237f, 23f, 52f, 28f, mint, 14f); word("ON", 250f, 43f, 14f, green)
                word("Host PC IP", 30f, 81f)
                box(28f, 91f, 264f, 34f, Color.rgb(237, 243, 240))
                word("___ . ___ . ___ . ___", 46f, 113f, 17f, green)
                box(217f, 139f, 74f, 26f, green); word("OK", 244f, 157f, 13f, Color.WHITE)
            }
        }
        c.restore()
    }
}
