// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.readiz.wgtinstaller.core.Phase
import dev.readiz.wgtinstaller.core.Update
import org.junit.Assert.*
import org.junit.Test

class BrowserPromptTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val url = "https://example.invalid/login"
    private fun dialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("loginDialog")
        .apply { isAccessible = true }.get(activity) as? AlertDialog
    private fun browserMonitor() = instrumentation.addMonitor(
        IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") },
        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)

    @Test fun waitsForConfirmationAndSurvivesRecreation() {
        val original = AppLanguage.selected(context)
        try {
            for (language in listOf("en", "ko")) {
                InstallState.finish(); AppLanguage.save(context, language)
                val monitor = browserMonitor()
                try {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        scenario.onActivity {
                            assertTrue(InstallState.begin("192.168.1.42"))
                            InstallState.publish(Update(Phase.LOGIN, "", browserUrl = url))
                        }
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            assertTrue(requireNotNull(dialog(activity)).isShowing)
                            assertEquals(url, InstallState.pendingBrowserUrl())
                            assertEquals(0, monitor.hits)
                        }
                        scenario.recreate()
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            assertTrue(requireNotNull(dialog(activity)).isShowing)
                            assertEquals(0, monitor.hits)
                        }
                        if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
                            Thread.sleep(650)
                            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                            java.io.File(context.getExternalFilesDir(null), "login-$language.png").outputStream().use {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                        scenario.onActivity { activity ->
                            requireNotNull(dialog(activity)).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        }
                        instrumentation.waitForIdleSync()
                        assertEquals(1, monitor.hits)
                        assertNull(InstallState.pendingBrowserUrl())
                        scenario.recreate()
                        scenario.onActivity { assertNull(dialog(it)) }
                        assertEquals(1, monitor.hits)
                    }
                } finally { instrumentation.removeMonitor(monitor); InstallState.finish() }
            }
        } finally { AppLanguage.save(context, original) }
    }

    @Test fun finishedInstallationDismissesUnconfirmedPrompt() {
        InstallState.finish()
        val monitor = browserMonitor()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    InstallState.begin("192.168.1.42")
                    InstallState.publish(Update(Phase.LOGIN, "", browserUrl = url))
                }
                instrumentation.waitForIdleSync()
                lateinit var prompt: AlertDialog
                scenario.onActivity { prompt = requireNotNull(dialog(it)); assertTrue(prompt.isShowing) }
                InstallState.finish()
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertFalse(prompt.isShowing); assertNull(dialog(it)) }
                assertNull(InstallState.pendingBrowserUrl())
                assertEquals(0, monitor.hits)
            }
        } finally { instrumentation.removeMonitor(monitor); InstallState.finish() }
    }
}
