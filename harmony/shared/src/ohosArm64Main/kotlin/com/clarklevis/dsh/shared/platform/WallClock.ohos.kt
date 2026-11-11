@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.clarklevis.dsh.shared.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

internal actual fun platformNowEpochMilliseconds(): Long =
    memScoped {
        val time = alloc<timeval>()
        check(gettimeofday(time.ptr, null) == 0) { "系统时钟读取失败" }
        time.tv_sec * 1000L + time.tv_usec / 1000L
    }
