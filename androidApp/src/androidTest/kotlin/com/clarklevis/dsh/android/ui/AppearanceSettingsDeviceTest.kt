package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class AppearanceSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun languageRowSavesSelectionAndRestoresSystemLocaleOnNextLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("language-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val appearance = AppearanceSettings(preferences)
        val originalLocale = Locale.getDefault()
        try {
            compose.setContent { DshTheme(appearance) { LanguageSettingsRow() } }
            compose.onNodeWithText("语言").performClick()
            compose.onNodeWithText("简体中文").performClick()
            compose.onNodeWithText("简体中文").assertIsDisplayed()
            compose.runOnIdle {
                val saved = AppearanceSettings(preferences)
                assertEquals(AppLanguage.SIMPLIFIED_CHINESE, saved.language)
                val englishContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(Locale.US)
                })
                val chineseContext = AppearanceSettings.localizedContext(englishContext, saved.language)
                assertEquals("zh", chineseContext.resources.configuration.locales[0].language)
                assertEquals("zh", Locale.getDefault().language)
            }
            compose.onNodeWithText("语言").performClick()
            compose.onNodeWithText("跟随系统").performClick()
            compose.runOnIdle {
                val saved = AppearanceSettings(preferences)
                assertEquals(AppLanguage.SYSTEM, saved.language)
                val englishContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(Locale.US)
                })
                val systemContext = AppearanceSettings.localizedContext(englishContext, saved.language)
                assertEquals("en", systemContext.resources.configuration.locales[0].language)
                assertEquals("en", Locale.getDefault().language)
            }
        } finally {
            Locale.setDefault(originalLocale)
            preferences.edit().clear().commit()
        }
    }

    @Test fun themeRowSwitchesImmediatelyPersistsAndReturnsToSystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("appearance-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val appearance = AppearanceSettings(preferences)
        var systemDark by mutableStateOf(false)
        try {
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    DshTheme(appearance) {
                        Column {
                            InterfaceStyleSettingsRow()
                            Text(if (isSystemInDarkTheme()) "effective-dark" else "effective-light")
                        }
                    }
                }
            }
            compose.onNodeWithText("effective-light").assertIsDisplayed()
            compose.onNodeWithText("界面").performClick()
            compose.onNodeWithText("深色").performClick()
            compose.onNodeWithText("effective-dark").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(InterfaceStyle.DARK, AppearanceSettings(preferences).interfaceStyle)
            }
            compose.onNodeWithText("界面").performClick()
            compose.onNodeWithText("浅色").performClick()
            compose.runOnIdle { systemDark = true }
            compose.onNodeWithText("effective-light").assertIsDisplayed()
            compose.onNodeWithText("界面").performClick()
            compose.onNodeWithText("跟随系统").performClick()
            compose.onNodeWithText("effective-dark").assertIsDisplayed()
            compose.runOnIdle { systemDark = false }
            compose.onNodeWithText("effective-light").assertIsDisplayed()
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
