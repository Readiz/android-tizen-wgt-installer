// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.os.Handler
import android.os.Looper
import dev.readiz.wgtinstaller.core.*
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory UI bridge. Secrets and Samsung tokens are never added to state or logs. */
object InstallState {
    data class Entry(val update: Update, val errorCode: String? = null)
    data class Snapshot(val update: Update = Update(Phase.IDLE, ""), val running: Boolean = false, val ip: String = "",
        val logs: List<Entry> = emptyList(), val errorCode: String? = null)
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    @Volatile var snapshot = Snapshot(); private set
    private var pendingBrowser: String? = null
    private var lastProgressAt = 0L
    fun observe(callback: (Snapshot) -> Unit) { observers.add(callback); callback(snapshot) }
    fun remove(callback: (Snapshot) -> Unit) { observers.remove(callback) }
    @Synchronized fun begin(ip: String): Boolean {
        if (snapshot.running) return false
        pendingBrowser = null; snapshot = Snapshot(Update(Phase.CONNECTING, ""), true, ip); dispatch(); return true
    }
    @Synchronized fun publish(update: Update, errorCode: String? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (update.phase == snapshot.update.phase && update.progress in 0..99 && now - lastProgressAt < 150) return
        lastProgressAt = now
        if (update.browserUrl != null) pendingBrowser = update.browserUrl
        val clean = update.copy(browserUrl = null)
        val previous = snapshot.update
        val logs = if (previous.phase == update.phase && previous.message == update.message) snapshot.logs
            else (snapshot.logs + Entry(clean, errorCode)).takeLast(60)
        snapshot = snapshot.copy(update = clean, logs = logs, errorCode = errorCode); dispatch()
    }
    @Synchronized fun finish() { snapshot = snapshot.copy(running = false); pendingBrowser = null; dispatch() }
    @Synchronized fun pendingBrowserUrl(): String? = pendingBrowser
    @Synchronized fun takeBrowserUrl(expected: String): String? =
        if (pendingBrowser == expected) pendingBrowser.also { pendingBrowser = null } else null
    private fun dispatch() { val value = snapshot; main.post { observers.forEach { it(value) } } }
}
