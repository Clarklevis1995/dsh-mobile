package com.clarklevis.dsh.shared

/**
 * KMP 模块的最小稳定入口。
 *
 * 阶段 7 后续迁移会在此模块内增加 protocol、domain、data 和 usecase，
 * Android 与 SwiftUI 都只依赖公开 facade，不直接耦合内部 Reducer 实现。
 */
object SharedModuleInfo {
    const val NAME = "DeepSeekHarnessShared"
    const val SCHEMA_VERSION = 1

    val sourceSets: List<String> = listOf("commonMain", "androidMain", "iosMain")

    fun summary(): String = "$NAME · schema $SCHEMA_VERSION"
}
