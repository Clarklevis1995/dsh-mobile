package com.clarklevis.dsh.android

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import java.util.Locale

/** 仅 Debug 构建包含的布局验收宿主，所有页面由设备测试注入。 */
class BotDesignTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AppScreenOrientation.apply(this)
    }

    override fun attachBaseContext(newBase: Context) {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val configuration = Configuration().apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // 生产 Activity 旋转时会重建并重新应用应用语言；测试宿主保持内容，需同步语言。
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        super.onConfigurationChanged(Configuration(newConfig).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }
}
