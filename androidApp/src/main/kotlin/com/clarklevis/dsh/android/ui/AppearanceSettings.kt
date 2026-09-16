package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

internal enum class InterfaceStyle(val title: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

internal enum class AppLanguage(val title: String) {
    SYSTEM("跟随系统"), SIMPLIFIED_CHINESE("简体中文");
}

internal class AppearanceSettings(private val preferences: SharedPreferences) {
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
            if (language == AppLanguage.SYSTEM) {
                Locale.setDefault(context.resources.configuration.locales[0])
                return context
            }
            val locale = Locale.SIMPLIFIED_CHINESE
            Locale.setDefault(locale)
            return context.createConfigurationContext(Configuration().apply {
                setLocale(locale)
            })
        }
    }
}

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
