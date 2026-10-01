// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    fun selected(context: Context): String =
        if (context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("language", "en") == "ko") "ko" else "en"

    fun save(context: Context, language: String) {
        require(language in listOf("en", "ko"))
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putString("language", language).apply()
    }

    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(selected(context))))
        return context.createConfigurationContext(config)
    }
}
