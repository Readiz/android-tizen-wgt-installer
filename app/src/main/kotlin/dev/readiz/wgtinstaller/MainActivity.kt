// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.*
import dev.readiz.wgtinstaller.core.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var repo: EditText
    private lateinit var ip: EditText
    private lateinit var trust: CheckBox
    private lateinit var install: Button
    private lateinit var inspect: Button
    private lateinit var youtube: Button
    private lateinit var scan: Button
    private lateinit var stop: Button
    private lateinit var language: Spinner
    private lateinit var chooseLocal: Button
    private lateinit var advanced: LinearLayout
    private lateinit var appCredit: TextView
    private lateinit var appHeading: TextView
    private lateinit var appSummary: TextView
    private var local: LocalWidget? = null
    private lateinit var selection: TextView
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var progress: ProgressBar
    private lateinit var devices: LinearLayout
    private lateinit var files: RadioGroup
    private lateinit var release: LinearLayout
    private lateinit var manual: LinearLayout
    private lateinit var activitySection: LinearLayout
    private lateinit var scroll: ScrollView
    private var wasRunning = false
    private var assets: List<ReleaseAsset> = emptyList()
    private var selected: ReleaseAsset? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var token = Cancellation()
    private var busy = false
    private var searching = false
    private var visible = false
    private var loginDialog: AlertDialog? = null
    private var loginDialogUrl: String? = null
    private val ink = Color.rgb(28, 33, 38)
    private val muted = Color.rgb(92, 99, 106)
    private val accent = Color.rgb(16, 108, 87)
    private val observer: (InstallState.Snapshot) -> Unit = { render(it) }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = getSharedPreferences("setup", MODE_PRIVATE)
        assets = savedInstanceState?.getStringArrayList("assets")?.mapNotNull { runCatching { ReleaseAsset.decode(it) }.getOrNull() }.orEmpty()
        local = savedInstanceState?.getString("local")?.let { runCatching { LocalWidget.decode(it) }.getOrNull() }
        selected = assets.firstOrNull { it.assetId == savedInstanceState?.getLong("selected") }
        val root = column().apply { setBackgroundColor(Color.WHITE) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val body = column().apply { setPadding(dp(20), dp(12), dp(20), dp(16)); isFocusableInTouchMode = true }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(text(getString(R.string.app_name), 24, true).apply {
            setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        language = Spinner(this).apply {
            contentDescription = getString(R.string.language); minimumHeight = dp(48)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("English", "한국어"))
            setSelection(if (AppLanguage.selected(this@MainActivity) == "ko") 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val next = if (position == 1) "ko" else "en"
                    if (next != AppLanguage.selected(this@MainActivity)) { AppLanguage.save(this@MainActivity, next); recreate() }
                }
            }
        }
        header.addView(language, LinearLayout.LayoutParams(-2, -2))
        header.addView(iconButton(R.string.about, R.string.short_about, R.drawable.ic_info) { showAbout() })
        body.addView(header)

        divider(body)
        body.addView(titleRow(text(getString(R.string.tv_title), 18, true),
            iconButton(R.string.setup_title, R.string.short_guide, R.drawable.ic_info) {
                startActivity(Intent(this, SetupGuideActivity::class.java))
            }))
        scan = button(R.string.find_tv) { discover() }; body.addView(scan)
        devices = column(); body.addView(devices)
        manual = column(); manual.addView(text(getString(R.string.tv_address), 13, true))
        ip = input(R.string.tv_address_hint).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(savedInstanceState?.getString("ip") ?: saved.getString("ip", ""))
        }; manual.addView(ip)
        disclosure(body, R.string.manual_ip, manual, savedInstanceState?.getBoolean("manual") ?: ip.text.isNotBlank())
        divider(body)
        appHeading = text(getString(R.string.app_title), 18, true)
        val sourceOptions = iconButton(R.string.other_apps, R.string.short_apps, R.drawable.ic_download) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        body.addView(titleRow(appHeading, sourceOptions))
        appSummary = text(getString(R.string.youtube_description), 15, true); body.addView(appSummary)
        appCredit = text(getString(R.string.youtube_credit), 12).apply { setTextColor(muted) }; body.addView(appCredit)
        youtube = button(R.string.youtube) {
            if (busy || InstallState.snapshot.running) return@button
            repo.setText(R.string.youtube_repo); resolve()
        }; body.addView(youtube)
        advanced = column()
        advanced.addView(text(getString(R.string.repo_label), 13, true))
        repo = input(R.string.repo_hint).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(savedInstanceState?.getString("repo") ?: getString(R.string.youtube_repo))
        }; advanced.addView(repo)
        inspect = button(R.string.find_app) { resolve() }; advanced.addView(inspect)
        chooseLocal = button(R.string.choose_local) {
            if (!busy && !InstallState.snapshot.running) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 21)
            }
        }; advanced.addView(chooseLocal)
        advanced.addView(text(getString(R.string.local_note), 12).apply { setTextColor(muted) })
        advanced.visibility = if (savedInstanceState?.getBoolean("advanced") == true) View.VISIBLE else View.GONE
        body.addView(advanced)
        release = column(); body.addView(release)
        selection = text("", 15, true); release.addView(selection)
        files = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }; release.addView(files)
        trust = CheckBox(this).apply {
            text = getString(R.string.trust_source); textSize = 14f; minHeight = dp(48)
            isChecked = savedInstanceState?.getBoolean("trust") ?: false
            setOnCheckedChangeListener { _, _ -> render(InstallState.snapshot) }
        }; release.addView(trust)

        activitySection = column(); body.addView(activitySection); divider(activitySection)
        status = text("", 16, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; activitySection.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        activitySection.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        stop = textButton(R.string.stop) {
            token.cancel()
            if (InstallState.snapshot.running) startService(Intent(this, InstallService::class.java).setAction("cancel"))
        }; activitySection.addView(stop)
        val technical = column()
        logs = text("", 12).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }; technical.addView(logs)
        disclosure(activitySection, R.string.details, technical)

        val footer = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)); setBackgroundColor(Color.rgb(247, 248, 249)) }
        install = button(R.string.install) { launch(selected) }.apply {
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.WHITE, muted)))
            compoundDrawableTintList = textColors
            backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(accent, Color.rgb(224, 228, 229)))
        }; footer.addView(install)
        root.addView(footer)
        repo.addTextChangedListener(watcher {
            clearRelease(); render(InstallState.snapshot)
        })
        ip.addTextChangedListener(watcher { render(InstallState.snapshot) })
        renderAssets()
        val previous = getSharedPreferences("install-journal", MODE_PRIVATE).getString("phase", null)
        if (previous != null && previous != Phase.COMPLETE.name && !InstallState.snapshot.running && InstallState.snapshot.update.phase == Phase.IDLE)
            InstallState.publish(Update(Phase.IDLE, "Previous installation interrupted"), "interrupted")
        render(InstallState.snapshot)
    }

    override fun onStart() { super.onStart(); visible = true; InstallState.observe(observer) }
    override fun onStop() {
        visible = false; InstallState.remove(observer)
        loginDialog?.dismiss(); loginDialog = null; loginDialogUrl = null
        super.onStop()
    }
    override fun onDestroy() { token.cancel(); worker.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("repo", repo.text.toString()); out.putString("ip", ip.text.toString())
        out.putBoolean("trust", trust.isChecked); out.putBoolean("manual", manual.visibility == View.VISIBLE)
        out.putBoolean("advanced", advanced.visibility == View.VISIBLE)
        local?.let { out.putString("local", it.encode()) }
        out.putStringArrayList("assets", ArrayList(assets.map { it.encode() })); selected?.let { out.putLong("selected", it.assetId) }
        super.onSaveInstanceState(out)
    }
    @Deprecated("Uses the platform document picker on API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK || busy || InstallState.snapshot.running) return
        val uri = data?.data ?: return
        clearRelease()
        token.cancel(); token = Cancellation(); val c = token; busy = true; render(InstallState.snapshot)
        worker.execute {
            val result = runCatching { LocalWgtStore(applicationContext).import(uri, c) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                if (runCatching { c.check() }.isSuccess) result.onSuccess {
                    local = it; renderAssets()
                }.onFailure { show(getString(R.string.error_local)) }
                render(InstallState.snapshot)
            }
        }
    }
    private fun show(message: String) {
        if (!isDestroyed && !isFinishing) AlertDialog.Builder(this).setMessage(message).setPositiveButton(R.string.ok, null).show()
    }
    private fun showAbout() {
        AlertDialog.Builder(this).setTitle(getString(R.string.app_name) + " · " + getString(R.string.experimental))
            .setMessage(R.string.about_body).setPositiveButton(R.string.ok, null)
            .show()
    }
    private fun renderAssets() {
        release.visibility = if (assets.isEmpty() && local == null) View.GONE else View.VISIBLE
        appSummary.text = if (local != null) getString(R.string.local_selected) else
            if (assets.firstOrNull()?.repo?.key?.let { it != "sushydev/tizen-youtube" } == true) getString(R.string.other_selected)
            else getString(R.string.youtube_description)
        val alternate = local != null || assets.firstOrNull()?.repo?.key?.let { it != "sushydev/tizen-youtube" } == true
        appHeading.setText(if (alternate) R.string.alternate_title else R.string.app_title)
        appCredit.visibility = if (alternate) View.GONE else View.VISIBLE
        trust.setText(if (local != null) R.string.trust_local else R.string.trust_source)
        files.removeAllViews()
        local?.let {
            selection.text = getString(R.string.file_size, it.name, it.size / 1024)
            return
        }
        val first = assets.firstOrNull() ?: return
        selection.text = getString(R.string.release_summary, first.repo.fullName, first.tag)
        if (assets.size > 1) {
            files.addView(text(getString(R.string.choose_file), 14, true))
            files.addView(text(getString(if (first.repo.key == "sushydev/tizen-youtube") R.string.youtube_hint else R.string.choose_file_hint), 13))
        }
        assets.forEach { asset ->
            files.addView(RadioButton(this).apply {
                id = View.generateViewId(); textSize = 14f; minHeight = dp(48)
                text = if (asset.sha256 == null) getString(R.string.file_unverified, asset.name) else getString(R.string.file_size, asset.name, asset.size / 1024)
                isChecked = selected?.assetId == asset.assetId; isEnabled = asset.sha256 != null
                setOnClickListener { selected = asset; trust.isChecked = false; render(InstallState.snapshot) }
            })
        }
    }
    private fun clearRelease() {
        assets = emptyList(); selected = null; local = null
        // Checkbox listeners render synchronously, so remove the old file views first.
        renderAssets()
        trust.isChecked = false
    }
    private fun resolve() {
        if (busy || InstallState.snapshot.running) return
        val source = runCatching { RepoRef.parse(repo.text.toString()).fullName }.getOrElse { repo.error = getString(R.string.error_repo); return }
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(repo.windowToken, 0)
        repo.clearFocus()
        clearRelease()
        token.cancel(); token = Cancellation(); val c = token; busy = true; render(InstallState.snapshot)
        worker.execute {
            val result = runCatching { GitHubReleases(SafeHttp(c)).inspect(source).also { c.check() } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                if (runCatching { c.check() }.isSuccess) result.onSuccess { catalog ->
                    getSharedPreferences("setup", MODE_PRIVATE).edit().putString("repo", source).apply()
                    assets = catalog.assets; selected = assets.singleOrNull()?.takeIf { it.sha256 != null }; renderAssets()
                }.onFailure { show(AppMessages.error(this, AppMessages.errorCode(it as? Exception ?: Exception(it)))) }
                render(InstallState.snapshot)
            }
        }
    }
    private fun launch(asset: ReleaseAsset?) {
        if (busy || InstallState.snapshot.running) return
        if (asset == null && local == null) { show(getString(R.string.error_selection)); return }
        if (asset != null && asset.sha256 == null) { show(getString(R.string.error_digest)); return }
        if (!trust.isChecked) { show(getString(R.string.error_trust)); return }
        val host = runCatching { Safety.privateIpv4(ip.text.toString()) }.getOrElse {
            manual.visibility = View.VISIBLE; ip.error = getString(R.string.error_ip); return
        }
        val request = Intent(this, InstallService::class.java)
            .putExtra("ip", host).putExtra("trust", true)
        local?.let { request.putExtra("local", it.encode()) } ?: asset?.let { request.putExtra("asset", it.encode()) }
        getSharedPreferences("setup", MODE_PRIVATE).edit().putString("ip", host).apply()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        try { startForegroundService(request) } catch (_: Exception) { show(getString(R.string.error_service)) }
    }
    private fun discover() {
        if (busy || InstallState.snapshot.running) return
        token.cancel(); token = Cancellation(); val c = token; busy = true; searching = true
        devices.removeAllViews(); render(InstallState.snapshot)
        worker.execute {
            try {
                WifiLan(this).discover({ tv -> runOnUiThread {
                    if (!isDestroyed && runCatching { c.check() }.isSuccess) devices.addView(button(tv.name + " · " + tv.ip) {
                        ip.setText(tv.ip); manual.visibility = View.VISIBLE
                    })
                } }, c)
            } catch (e: Exception) {
                runOnUiThread { if (runCatching { c.check() }.isSuccess) show(AppMessages.error(this, AppMessages.errorCode(e))) }
            } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        busy = false; searching = false; render(InstallState.snapshot)
                        if (devices.childCount == 0 && runCatching { c.check() }.isSuccess) devices.addView(text(getString(R.string.no_tv), 14))
                    }
                }
            }
        }
    }
    private fun render(s: InstallState.Snapshot) {
        if (isDestroyed) return
        val free = !busy && !s.running
        language.isEnabled = free; repo.isEnabled = free; ip.isEnabled = free; scan.isEnabled = free
        inspect.isEnabled = free; trust.isEnabled = free && (selected != null || local != null); youtube.isEnabled = free; chooseLocal.isEnabled = free
        for (i in 0 until devices.childCount) devices.getChildAt(i).isEnabled = free
        var assetIndex = 0
        for (i in 0 until files.childCount) {
            val child = files.getChildAt(i)
            if (child is RadioButton) child.isEnabled = free && assets[assetIndex++].sha256 != null
        }
        install.isEnabled = free && (selected != null || local != null) && trust.isChecked && ip.text.isNotBlank()
        install.setText(if (s.running) R.string.installing else R.string.install)
        inspect.setText(if (busy && !searching) R.string.finding_app else R.string.find_app)
        scan.setText(if (searching) R.string.searching else R.string.find_tv)
        activitySection.visibility = if (busy || s.running || s.logs.isNotEmpty()) View.VISIBLE else View.GONE
        status.text = if (busy) getString(if (searching) R.string.searching else R.string.finding_app) else AppMessages.status(this, s.update, s.errorCode)
        status.setTextColor(if (s.update.phase == Phase.ERROR && !busy) Color.rgb(164, 44, 40) else ink)
        logs.text = if (s.logs.isEmpty()) getString(R.string.no_details) else s.logs.joinToString("\n\n") { entry ->
            AppMessages.status(this, entry.update, entry.errorCode) +
                (entry.errorCode?.let { "\n[$it]\n${entry.update.message}" } ?: "")
        }
        progress.visibility = if (s.running || busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = busy || s.update.progress < 0
        if (!busy && s.update.progress >= 0) progress.progress = s.update.progress
        stop.visibility = if (busy || s.running) View.VISIBLE else View.GONE
        if (s.running && !wasRunning) scroll.post { scroll.smoothScrollTo(0, activitySection.top) }
        wasRunning = s.running
        if (visible) showLoginPrompt()
    }
    private fun showLoginPrompt() {
        val url = InstallState.pendingBrowserUrl()
        if (url == loginDialogUrl) return
        loginDialog?.dismiss(); loginDialog = null; loginDialogUrl = url
        if (url == null || isFinishing) return
        fun cancelLogin() {
            if (InstallState.takeBrowserUrl(url) != null) stop.performClick()
        }
        loginDialog = AlertDialog.Builder(this)
            .setTitle(R.string.login_title)
            .setMessage(R.string.install_note)
            .setPositiveButton(R.string.login_continue) { _, _ ->
                InstallState.takeBrowserUrl(url)?.let { pending ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending))) }
                        .onFailure { show(getString(R.string.error_browser)) }
                }
            }
            .setNegativeButton(R.string.stop) { _, _ -> cancelLogin() }
            .setOnCancelListener { cancelLogin() }
            .create().also { it.setCanceledOnTouchOutside(false); it.show() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(ink); letterSpacing = 0f
        setPadding(0, dp(5), 0, dp(7)); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun input(hint: Int) = EditText(this).apply {
        setHint(hint); setSingleLine(true); textSize = 16f; minHeight = dp(52)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private fun button(label: Int, action: () -> Unit) = button(getString(label), action).apply {
        val icon = when (label) {
            R.string.find_tv -> R.drawable.ic_tv_action
            R.string.youtube -> R.drawable.ic_play
            R.string.find_app -> R.drawable.ic_search
            R.string.choose_local -> R.drawable.ic_folder
            R.string.install -> R.drawable.ic_download
            else -> 0
        }
        if (icon != 0) actionIcon(icon)
    }
    private fun Button.actionIcon(icon: Int) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = dp(12)
        compoundDrawableTintList = textColors
        setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; letterSpacing = 0f; minHeight = dp(48); setOnClickListener { action() }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(accent, muted)))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 16, 108, 87)), GradientDrawable().apply {
            setColor(Color.rgb(236, 245, 241)); cornerRadius = dp(6).toFloat()
        }, null)
        stateListAnimator = null; elevation = 0f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }
    private fun textButton(label: Int, action: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        setText(label); isAllCaps = false; textSize = 14f; letterSpacing = 0f; setTextColor(accent)
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; minHeight = dp(48)
        setOnClickListener { action() }
    }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(Color.rgb(230, 233, 235)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(12) })
    }
    private fun iconButton(label: Int, shortLabel: Int, icon: Int, action: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(shortLabel); isAllCaps = false; textSize = 12f; letterSpacing = 0f
            setTextColor(muted)
            val drawable = getDrawable(icon)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }
            setCompoundDrawablesRelative(drawable, null, null, null)
            compoundDrawableTintList = textColors; compoundDrawablePadding = dp(4)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
            contentDescription = getString(label); tooltipText = getString(label)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setOnClickListener { action() }
        }
    private fun titleRow(title: TextView, action: View) = LinearLayout(this).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        addView(action)
    }
    private fun disclosure(parent: LinearLayout, title: Int, content: LinearLayout, expanded: Boolean = false, onExpand: () -> Unit = {}) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        val toggle = textButton(title) { content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        fun updateIcon() {
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                if (content.visibility == View.VISIBLE) R.drawable.ic_expand_less else R.drawable.ic_expand_more, 0)
        }
        toggle.setOnClickListener {
            content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (content.visibility == View.VISIBLE) onExpand()
            updateIcon()
        }
        updateIcon()
        parent.addView(toggle); parent.addView(content)
    }
    private fun watcher(change: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = change()
        override fun afterTextChanged(s: Editable?) = Unit
    }
}
