package com.sc.mf919pro

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Prefix rather than equality: debug and stag carry the .dev / .uat
        // applicationIdSuffix, so only release is bare "com.sc.mf919pro".
        assertTrue(appContext.packageName.startsWith("com.sc.mf919pro"))
    }
}
