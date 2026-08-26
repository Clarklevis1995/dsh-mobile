package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.SharedModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedModuleSmokeTest {
    @Test
    fun androidApplicationCanCallSharedModule() {
        assertEquals("DeepSeekHarnessShared · schema 1", SharedModuleInfo.summary())
    }
}
