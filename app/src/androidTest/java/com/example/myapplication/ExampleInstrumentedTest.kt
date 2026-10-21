package com.example.myapplication

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }

    @Test
    fun localJapaneseResourceIsAvailableWithoutPulledKey() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(appContext.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        }
        val japaneseContext = appContext.createConfigurationContext(configuration)

        assertEquals(
            "ローカル通知",
            japaneseContext.getString(R.string.local_notice),
        )
    }
}
