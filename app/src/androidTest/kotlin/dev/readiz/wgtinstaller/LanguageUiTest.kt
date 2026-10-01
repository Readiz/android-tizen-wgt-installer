// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.RadioButton
import dev.readiz.wgtinstaller.core.Phase
import dev.readiz.wgtinstaller.core.Update
import dev.readiz.wgtinstaller.core.ReleaseAsset
import dev.readiz.wgtinstaller.core.RepoRef
import java.util.Locale

class LanguageUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var oldLanguage: String? = null

    @Before fun setUp() {
        oldLanguage = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("language", null)
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().remove("language").commit()
    }

    @After fun tearDown() {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putString("language", oldLanguage).commit()
    }

    @Test fun testEnglishDefaultEvenOnKoreanPhone() {
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.KOREAN)) }
        val koreanPhone = context.createConfigurationContext(config)
        assertEquals("en", AppLanguage.selected(koreanPhone))
        assertEquals("Install after steps 1 and 2", AppLanguage.wrap(koreanPhone).getString(R.string.install))
    }

    @Test fun testLanguagePersistsAcrossContexts() {
        AppLanguage.save(context, "ko")
        val fresh = context.createConfigurationContext(Configuration(context.resources.configuration))
        assertEquals("ko", AppLanguage.selected(fresh))
        assertEquals("1·2단계 완료 후 설치", AppLanguage.wrap(fresh).getString(R.string.install))
        AppLanguage.save(context, "en")
        assertEquals("Install after steps 1 and 2", AppLanguage.wrap(fresh).getString(R.string.install))
    }

    @Test fun testStatusAndErrorsUseSelectedLanguage() {
        val raw = "원본 프로토콜 메시지"
        for (language in listOf("en", "ko")) {
            AppLanguage.save(context, language)
            val localized = AppLanguage.wrap(context)
            for (phase in Phase.entries) {
                val message = AppMessages.status(localized, Update(phase, raw))
                assertFalse(message.contains(raw))
                assertEquals(language == "ko", message.any { it in '\uac00'..'\ud7a3' })
            }
            assertEquals(localized.getString(R.string.error_keys), AppMessages.error(localized, "author.lost"))
            assertEquals(localized.getString(R.string.error_unknown), AppMessages.error(localized, "install.unknown"))
        }
    }

    @Test fun testInitialUiAndInvalidRepository() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                val install = views.filterIsInstance<Button>().single { it.text.toString() == "Install after steps 1 and 2" }
                assertFalse(install.isEnabled)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "1. Your TV" })
                val repo = views.filterIsInstance<EditText>().single { it.hint.toString() == "owner/repo or GitHub URL" }
                repo.setText("invalid")
                views.filterIsInstance<Button>().single { it.text.toString() == "Find app" }.performClick()
                assertEquals(activity.getString(R.string.error_repo), repo.error.toString())
                assertFalse(install.isEnabled)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun testLanguageSwitchKeepsInputsAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                views.filterIsInstance<EditText>().single { it.hint.toString() == "owner/repo or GitHub URL" }.setText("example/app")
                views.filterIsInstance<EditText>().single { it.hint.toString() == "e.g. 192.168.0.20" }.setText("192.168.1.42")
                views.filterIsInstance<Spinner>().single().setSelection(1)
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("ko", activity.resources.configuration.locales[0].language)
                val inputs = descendants(activity.window.decorView).filterIsInstance<EditText>().map { it.text.toString() }
                assertTrue(inputs.contains("example/app"))
                assertTrue(inputs.contains("192.168.1.42"))
                assertEquals("1·2단계 완료 후 설치", activity.getString(R.string.install))
            }
        }
    }

    @Test fun testDefaultYoutubeAndOptionalSources() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                val repo = views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.repo_hint) }
                assertEquals("SushyDev/tizen-youtube", repo.text.toString())
                assertFalse(repo.isShown)
                assertTrue(views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.youtube) }.isShown)
                assertFalse(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.setup_code_body) })
                views.single { it.contentDescription == activity.getString(R.string.other_apps) }.performClick()
                assertTrue(repo.isShown)
                assertTrue(views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.choose_local) }.isShown)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(descendants(activity.window.decorView).filterIsInstance<EditText>()
                    .single { it.hint == activity.getString(R.string.repo_hint) }.isShown)
            }
        }
    }

    @Test fun testSetupGuideInBothLanguages() {
        for (language in listOf("en", "ko")) {
            AppLanguage.save(context, language)
            ActivityScenario.launch(SetupGuideActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView)
                    assertTrue(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.guide_title) })
                    assertTrue(views.filterIsInstance<TextView>().single { it.text == activity.getString(R.string.setup_code_body) }.text.contains("123"))
                    assertTrue(views.any { it.contentDescription == activity.getString(R.string.setup_code_keys) })
                    assertTrue(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.setup_keep_host) })
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(descendants(activity.window.decorView).any { it.contentDescription == activity.getString(R.string.setup_code_keys) })
                }
            }
        }
    }

    @Test fun testLocalSelectionCannotCarryGithubConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                seedSelectedRelease(activity)
                MainActivity::class.java.getDeclaredMethod("clearRelease").apply { isAccessible = true }.invoke(activity)
                val local = dev.readiz.wgtinstaller.core.LocalWidget("selected.wgt", 100, "a".repeat(64))
                MainActivity::class.java.getDeclaredField("local").apply { isAccessible = true }.set(activity, local)
                MainActivity::class.java.getDeclaredMethod("renderAssets").apply { isAccessible = true }.invoke(activity)
                val views = descendants(activity.window.decorView)
                assertFalse(views.filterIsInstance<CheckBox>().single().isChecked)
                assertEquals(activity.getString(R.string.trust_local), views.filterIsInstance<CheckBox>().single().text.toString())
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("selected.wgt") })
                views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.repo_hint) }.setText("another/repo")
                assertReleaseCleared(activity)
            }
        }
    }

    @Test fun testDocumentPickerImportAndGuideReturn() {
        val uri = android.net.Uri.parse("content://dev.readiz.wgtinstaller.test.documents/fixture.wgt")
        val imported = LocalWgtStore(context).import(uri, dev.readiz.wgtinstaller.core.Cancellation())
        assertEquals("Fixture001", imported.verify(LocalWgtStore(context).read(imported, dev.readiz.wgtinstaller.core.Cancellation())).packageId)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                seedSelectedRelease(activity)
                MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                    .apply { isAccessible = true }.invoke(activity, 21, android.app.Activity.RESULT_OK, Intent().setData(uri))
            }
            val deadline = System.currentTimeMillis() + 5000
            var loaded = false
            while (!loaded && System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> loaded = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("fixture.wgt") } }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue("Document provider import completed", loaded)
            val monitor = instrumentation.addMonitor(SetupGuideActivity::class.java.name, null, false)
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                assertFalse(views.filterIsInstance<CheckBox>().single().isChecked)
                views.filterIsInstance<CheckBox>().single().isChecked = true
                assertTrue(views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.install) }.isEnabled)
                views.single { it.contentDescription == activity.getString(R.string.setup_title) }.performClick()
            }
            val guide = instrumentation.waitForMonitorWithTimeout(monitor, 3000)
            assertNotNull(guide)
            instrumentation.runOnMainSync {
                val back = descendants(guide.window.decorView).filterIsInstance<android.widget.ImageButton>().single()
                assertEquals(guide.getString(R.string.guide_done), back.contentDescription)
                assertEquals((48 * guide.resources.displayMetrics.density).toInt(), back.width)
                assertNotNull(back.drawable)
                back.performClick()
            }
            instrumentation.removeMonitor(monitor)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("fixture.wgt") })
                assertTrue(views.filterIsInstance<CheckBox>().single().isChecked)
            }
        }
    }

    @Test fun captureLocalizedScreensForReview() {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            Thread.sleep(650) // Let activity transitions and the next rendered frame settle.
            val image = instrumentation.uiAutomation.takeScreenshot()
            val file = java.io.File(context.getExternalFilesDir(null), "$name.png")
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        for (language in listOf("en", "ko")) {
            AppLanguage.save(context, language)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                capture("home-$language")
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).single { it.contentDescription == activity.getString(R.string.other_apps) }.performClick()
                }
                scenario.onActivity { activity ->
                    val scroll = descendants(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().single()
                    scroll.scrollTo(0, (300 * activity.resources.displayMetrics.density).toInt())
                }
                capture("options-$language")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    seedSelectedRelease(activity, consent = false)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().single()
                        .scrollTo(0, (240 * activity.resources.displayMetrics.density).toInt())
                }
                capture("selection-$language")
            }
            ActivityScenario.launch(SetupGuideActivity::class.java).use { scenario ->
                capture("guide-$language")
                scenario.onActivity { activity ->
                    val scroll = descendants(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().single()
                    scroll.scrollTo(0, (520 * activity.resources.displayMetrics.density).toInt())
                }
                capture("guide-remote-$language")
            }
        }
    }

    @Test fun testSimulatorIsNotBundledInApp() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("dev.readiz.wgtinstaller.simulator.DemoEnvironment", false, context.classLoader)
        }
    }

    @Test fun testYoutubeCanBePressedAgainAfterConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                seedSelectedRelease(activity)
                val views = descendants(activity.window.decorView)
                val shortcut = views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.youtube) }
                shortcut.performClick()
                shortcut.performClick()
                assertReleaseCleared(activity)
            }
        }
    }

    @Test fun testFindAppCanBePressedAgainAfterConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                seedSelectedRelease(activity)
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.find_app) }.performClick()
                assertReleaseCleared(activity)
            }
        }
    }

    @Test fun testYoutubeCanBePressedAgainWithoutConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                seedSelectedRelease(activity, consent = false)
                val shortcut = descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == activity.getString(R.string.youtube) }
                shortcut.performClick()
                assertFalse(shortcut.isEnabled)
                shortcut.performClick()
                assertReleaseCleared(activity)
            }
        }
    }

    @Test fun testEditingRepositoryClearsPreviousConsentAndFiles() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                seedSelectedRelease(activity)
                descendants(activity.window.decorView).filterIsInstance<EditText>()
                    .single { it.hint == activity.getString(R.string.repo_hint) }.setText("another/app")
                assertReleaseCleared(activity)
            }
        }
    }

    private fun seedSelectedRelease(activity: MainActivity, consent: Boolean = true) {
        val views = descendants(activity.window.decorView)
        views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.repo_hint) }
            .setText("SushyDev/tizen-youtube")
        views.filterIsInstance<EditText>().single { it.hint == activity.getString(R.string.tv_address_hint) }
            .setText("192.168.1.42")
        val repo = RepoRef("SushyDev", "tizen-youtube")
        val assets = listOf("5.0", "5.5").mapIndexed { index, version ->
            ReleaseAsset(repo, 1, "v1.0", index + 1L, "tizen-$version.wgt", 1024,
                "https://github.com/${repo.fullName}/releases/download/v1.0/tizen-$version.wgt", "a".repeat(64))
        }
        // Seed a completed lookup without relying on GitHub availability or rate limits.
        MainActivity::class.java.getDeclaredField("assets").apply { isAccessible = true }.set(activity, assets)
        MainActivity::class.java.getDeclaredMethod("renderAssets").apply { isAccessible = true }.invoke(activity)
        descendants(activity.window.decorView).filterIsInstance<RadioButton>().last().performClick()
        views.filterIsInstance<CheckBox>().single().isChecked = consent
        assertEquals(consent, views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.install) }.isEnabled)
    }

    private fun assertReleaseCleared(activity: MainActivity) {
        val views = descendants(activity.window.decorView)
        assertTrue(views.filterIsInstance<RadioButton>().isEmpty())
        assertFalse(views.filterIsInstance<CheckBox>().single().isChecked)
        assertFalse(views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.install) }.isEnabled)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
