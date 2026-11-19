package com.clarklevis.dsh.android

import android.app.Activity
import android.content.pm.ActivityInfo

/** Manifest 保证小屏启动即竖屏，运行时按设备配置解除大屏方向限制。 */
internal object AppScreenOrientation {
    fun apply(activity: Activity) {
        activity.requestedOrientation = if (activity.resources.configuration.smallestScreenWidthDp < 600) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
