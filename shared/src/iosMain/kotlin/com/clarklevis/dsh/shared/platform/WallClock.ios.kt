package com.clarklevis.dsh.shared.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun platformNowEpochMilliseconds(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
