package com.mateof.kanal.core

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.core.os.ConfigurationCompat
import com.mateof.kanal.R
import java.util.Locale

/**
 * The languages Kanal ships in. [AUTO] follows the television's own language.
 *
 * Galician is the *default* resource set — `res/values/strings.xml` holds it,
 * with `values-es` and `values-en` beside it — so any language Kanal does not
 * translate lands on Galician without a line of code deciding it.
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    AUTO("", R.string.language_auto),
    GALEGO("gl", R.string.language_galego),
    CASTELAN("es", R.string.language_castelan),
    ENGLISH("en", R.string.language_english);

    companion object {
        val TRANSLATED = setOf("gl", "es", "en")
        const val FALLBACK = "gl"

        fun of(stored: String?): AppLanguage =
            entries.firstOrNull { it.name == stored } ?: AUTO
    }
}

/**
 * The language chosen in the device's own settings, or Galician when Kanal has
 * no translation for it.
 *
 * Read from the *system* resources on purpose: once the app applies its own
 * locale, `Locale.getDefault()` can no longer be trusted to report what the
 * television is set to.
 */
fun systemLanguageTag(): String =
    ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
        ?.language
        ?.takeIf { it in AppLanguage.TRANSLATED }
        ?: AppLanguage.FALLBACK

/** The locale to render in: the explicit choice, or whatever the device uses. */
fun AppLanguage.locale(): Locale = Locale(tag.ifEmpty { systemLanguageTag() })
