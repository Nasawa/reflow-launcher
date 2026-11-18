package dev.nasawa.configlauncher.hosted

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #299 NW-2: provider lookup per profile (personal only in this milestone, D3) and the fixed host ID. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetHostTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun findsAProviderByRelativeOrFullClassAndNoWorkOnes() {
        val info = AppWidgetProviderInfo().apply { provider = ComponentName("com.example.weather", "com.example.weather.WeatherWidget") }
        val list = listOf(AppWidgetProviderInfo().apply { provider = ComponentName("com.other", "com.other.W") }, info)
        assertSame(info, WidgetHost.find(list, "com.example.weather", ".WeatherWidget"))
        assertSame(info, WidgetHost.find(list, "com.example.weather", "com.example.weather.WeatherWidget"))
        assertNull(WidgetHost.find(list, "com.example.weather", ".Other"))
        // D3: work-profile widgets aren't hosted in this milestone.
        assertTrue(WidgetHost(app).providers("work").isEmpty())
    }

    @Test fun startStopAndIdsAreSafe() {
        val h = WidgetHost(app)
        h.start(); h.stop()
        assertEquals(0x5246, WidgetHost.HOST_ID)
        h.delete(12345)   // unknown ID: no crash
    }
}
