package com.clarklevis.dsh.shared.platform

/** 兼容鸿蒙 Kotlin 2.0 工具链；与业务无关的系统时钟由平台提供。 */
internal expect fun platformNowEpochMilliseconds(): Long
