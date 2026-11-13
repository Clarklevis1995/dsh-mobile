package com.clarklevis.dsh.android.platform

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

internal data class BackgroundBatteryStatus(
    val ignoresOptimizations: Boolean,
    val backgroundRestricted: Boolean,
) {
    val needsGuidance: Boolean get() = !ignoresOptimizations || backgroundRestricted
}

internal object AndroidBackgroundBattery {
    fun status(context: Context): BackgroundBatteryStatus = BackgroundBatteryStatus(
        ignoresOptimizations = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
        backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true,
    )

    /** 厂商后台管理无统一入口；优先使用系统接口，并逐级回退到应用详情。 */
    // 实时审批与任务通知依赖用户自托管网关的长连接；Doze 断网会中断该核心功能。
    // 用户主动点击“去设置”后才申请，适用官方说明的核心功能受限豁免场景：
    // https://developer.android.com/training/monitoring-device-state/doze-standby#exemption-cases
    @SuppressLint("BatteryLife")
    fun openSettings(context: Context, status: BackgroundBatteryStatus): Boolean {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        val intents = if (status.backgroundRestricted || status.ignoresOptimizations) {
            listOf(details, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                details,
            )
        }
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // 部分 ROM 不提供标准设置页面，继续尝试下一个入口。
            } catch (_: SecurityException) {
                // 厂商可能限制设置入口，不让引导导致应用崩溃。
            }
        }
        return false
    }
}
