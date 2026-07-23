package com.trubus.tams.data.repository

import android.content.Context
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Test-only Robolectric shadow -- no-ops WorkManager's own default
 * auto-initializing component (`androidx.work.WorkManagerInitializer`,
 * which since WorkManager 2.7.0 is an App Startup Initializer, not a
 * standalone ContentProvider).
 *
 * Robolectric recreates the Application -- and re-attaches manifest-
 * declared components -- fresh for EACH @Test method, by design,
 * for test isolation. Left un-shadowed, the real initializer's create()
 * builds WorkManager's own internal Room "WorkDatabase" from scratch
 * on every single test method; that is the dominant cost behind
 * MemberRepositoryTest's run time (see android/README.md's "Testing" section).
 *
 * Safe specifically for MemberRepositoryTest because nothing it exercises
 * (MemberRepository.login/postLocation/syncOfflineLocations) ever calls
 * WorkManager.getInstance(...) -- WorkManager is only ever touched by
 * LocationSyncWorker, which this test class never constructs.
 *
 * This class is compiled only into the test classpath (`src/test`) and is
 * never part of the production APK -- the real app keeps using the real
 * `WorkManagerInitializer` exactly as before; only Robolectric's in-JVM test
 * environment ever sees this substitute, and only for test classes that
 * explicitly opt in via `@Config(shadows = [ShadowWorkManagerInitializer::class])`.
 */
@Implements(WorkManagerInitializer::class)
class ShadowWorkManagerInitializer {

    @Implementation
    fun create(context: Context): WorkManager? {
        // Intentionally returns null to avoid triggering the real
        // initialization/Room database setup.
        return null
    }

    @Implementation
    fun dependencies(): List<Class<out Any>> {
        // App Startup Initializers can declare dependencies; we return none.
        return emptyList()
    }
}
