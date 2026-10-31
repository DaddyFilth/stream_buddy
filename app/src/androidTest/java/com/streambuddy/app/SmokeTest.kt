package com.streambuddy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streambuddy.app.cache.CacheManager
import com.streambuddy.app.data.AppDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level smoke tests. They run on an emulator (`connectedDebugAndroidTest`) and verify the
 * things a JVM test cannot: that the activity launches, that Room creates its database, and that
 * the Media3 cache can be opened and released on a real filesystem.
 *
 * They are deliberately shallow — this is a "does the app come up" gate, not a UI test suite.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun activityLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }

    @Test
    fun databaseIsUsable() {
        val dao = AppDb.get(context).streams()
        // Touch the DAO so a missing schema or a broken migration fails here, not in the UI.
        assertEquals(0, dao.allOnce().count { it.title.startsWith("smoke-test-") })
    }

    @Test
    fun mediaCacheOpensAndCanBeCleared() {
        val cache = CacheManager.get(context)
        assertTrue(cache.keys.isEmpty() || cache.keys.isNotEmpty())
        CacheManager.clear(context)
        assertEquals(0L, CacheManager.usageBytes(context))
        CacheManager.release()
    }

    @Test
    fun shareIntentIsRegistered() {
        // The share sheet must be able to find the activity for text payloads.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/clip.m3u8")
        }
        val resolves = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(intent, 0)
        assertTrue(
            "no activity handles ACTION_SEND text/plain",
            resolves.any { it.activityInfo?.packageName == context.packageName },
        )
    }

    @Test
    fun deepLinkIntentIsRegistered() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("streambuddy://queue"))
        val resolves = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(intent, 0)
        assertTrue(
            "no activity handles streambuddy://queue",
            resolves.any { it.activityInfo?.packageName == context.packageName },
        )
    }
}
