package com.clarklevis.dsh.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.clarklevis.dsh.android.ui.AppearanceSettings

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppearanceSettings.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 启动窗口已由 Starting 主题绘制，Activity 创建后切回常规主题。
        setTheme(R.style.Theme_DeepSeekHarness)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeepSeekHarnessAndroidApp()
        }
    }
}
