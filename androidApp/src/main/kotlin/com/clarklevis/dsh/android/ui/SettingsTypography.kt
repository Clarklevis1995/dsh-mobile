package com.clarklevis.dsh.android.ui

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 与 iOS SettingsTypography 对齐；字号继续由应用外观设置统一缩放。 */
internal object SettingsTypography {
    val item = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    val title = item.copy(fontWeight = FontWeight.SemiBold)
    val description = item.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val section = description
}
