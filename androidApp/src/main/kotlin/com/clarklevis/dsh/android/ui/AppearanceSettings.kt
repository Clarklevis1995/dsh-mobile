package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.localization.appTextKey
import java.util.Locale

internal enum class InterfaceStyle(private val sourceTitle: String) {
    SYSTEM(appTextKey("跟随系统")), LIGHT(appTextKey("浅色")), DARK(appTextKey("深色"));

    val title: String get() = appText(sourceTitle)

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

internal enum class AppLanguage {
    SYSTEM, SIMPLIFIED_CHINESE, ENGLISH;

    val title: String get() = when (this) {
        SYSTEM -> appText("跟随系统")
        SIMPLIFIED_CHINESE -> "简体中文" // i18n-ignore: language endonym, shown in its own language
        ENGLISH -> "English"
    }
}

internal fun appLocale(language: AppLanguage, systemLocale: Locale): Locale = when (language) {
    AppLanguage.SYSTEM -> systemLocale.takeIf { it.language in setOf("zh", "en") } ?: Locale.ENGLISH
    AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.ENGLISH -> Locale.ENGLISH
}

internal class AppearanceSettings(private val preferences: SharedPreferences) {
    var uiEffectsReduced by mutableStateOf(preferences.getBoolean("ui_effects_reduced", false))
        private set

    fun selectUiEffectsReduced(value: Boolean) {
        preferences.edit().putBoolean("ui_effects_reduced", value).apply()
        uiEffectsReduced = value
    }

    var fontScale by mutableStateOf(normalizeFontScale(preferences.getFloat("font_scale", 1f)))
        private set

    var fontFollowsSystem by mutableStateOf(preferences.getBoolean("font_follows_system", false))
        private set

    val effectiveFontScale: Float
        get() = if (fontFollowsSystem) 1f else fontScale

    fun selectFontFollowsSystem(value: Boolean) {
        preferences.edit().putBoolean("font_follows_system", value).apply()
        fontFollowsSystem = value
    }

    fun selectFontScale(value: Float) {
        val normalized = normalizeFontScale(value)
        preferences.edit().putFloat("font_scale", normalized).apply()
        fontScale = normalized
    }

    var interfaceStyle by mutableStateOf(
        InterfaceStyle.entries.firstOrNull { it.name == preferences.getString("interface_style", null) }
            ?: InterfaceStyle.SYSTEM
    )
        private set

    var language by mutableStateOf(
        AppLanguage.entries.firstOrNull { it.name == preferences.getString("language", null) }
            ?: AppLanguage.SYSTEM
    )
        private set

    fun selectInterfaceStyle(style: InterfaceStyle) {
        preferences.edit().putString("interface_style", style.name).apply()
        interfaceStyle = style
    }

    fun selectLanguage(value: AppLanguage) {
        preferences.edit().putString("language", value.name).apply()
        language = value
    }

    companion object {
        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("app_appearance", Context.MODE_PRIVATE)

        // 语言在 Activity 创建时应用，切换偏好不会中断当前会话。
        fun localizedContext(
            context: Context,
            language: AppLanguage = AppearanceSettings(preferences(context)).language
        ): Context {
            val locale = appLocale(language, Resources.getSystem().configuration.locales[0])
            Locale.setDefault(locale)
            return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(locale)
            })
        }
    }
}

internal fun normalizeFontScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.8f, 1.5f) else 1f

// 独立预览和组件测试默认保留完整效果。
internal val LocalUiEffectsReduced = staticCompositionLocalOf { false }

internal val LocalAppearanceSettings = staticCompositionLocalOf<AppearanceSettings> {
    error("AppearanceSettings must be provided by DshTheme")
}

@Composable
internal fun rememberAppearanceSettings(): AppearanceSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        AppearanceSettings(AppearanceSettings.preferences(context))
    }
}
