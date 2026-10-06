package com.fiilda.launcher

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAppSupportTest {
    @Test
    fun appLaunchResultReportsSuccessfulPlatformStart() {
        var startInvoked = false

        val result = appLaunchResult {
            startInvoked = true
        }

        assertEquals(AppLaunchResult.STARTED, result)
        assertTrue(startInvoked)
    }

    @Test
    fun appLaunchResultConvertsMissingActivityIntoRecoverableFailure() {
        val result = appLaunchResult {
            throw ActivityNotFoundException("removed activity")
        }

        assertEquals(AppLaunchResult.ACTIVITY_NOT_FOUND, result)
    }

    @Test
    fun appLaunchResultConvertsSecurityFailureIntoRecoverableFailure() {
        val result = appLaunchResult {
            throw SecurityException("launch denied")
        }

        assertEquals(AppLaunchResult.SECURITY_DENIED, result)
    }

    @Test(expected = IllegalStateException::class)
    fun appLaunchResultDoesNotHideUnrelatedRuntimeFailures() {
        appLaunchResult {
            throw IllegalStateException("unexpected launcher failure")
        }
    }

    @Test
    fun uninstallIntentTargetsTheRequestedPackage() {
        assertEquals("package:com.example.target", uninstallPackageUri("com.example.target"))
    }
}
