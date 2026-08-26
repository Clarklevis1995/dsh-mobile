package com.clarklevis.dsh.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedModuleInfoTest {
    @Test
    fun exposesStableBootstrapMetadata() {
        assertEquals("DeepSeekHarnessShared · schema 1", SharedModuleInfo.summary())
        assertTrue("androidMain" in SharedModuleInfo.sourceSets)
        assertTrue("iosMain" in SharedModuleInfo.sourceSets)
    }
}
