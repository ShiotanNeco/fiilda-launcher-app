package com.fiilda.launcher

import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamsungDynamicIconSourceTest {
    @Test
    fun targetForIsCaseInsensitiveAndKeepsExactComponent() {
        val component = ComponentName(
            "com.sec.android.app.clockpackage",
            "com.sec.android.app.clockpackage.ClockActivity",
        )

        val target = requireNotNull(SamsungDynamicIconSource.targetFor("SaMsUnG", component))

        assertEquals(SamsungDynamicIconSource.Kind.CLOCK, target.kind)
        assertEquals(component.packageName, target.packageName)
        assertEquals(component, target.component)
    }

    @Test
    fun targetForRejectsOtherManufacturersAndPackages() {
        val samsungClock = ComponentName(
            "com.sec.android.app.clockpackage",
            "com.sec.android.app.clockpackage.ClockActivity",
        )
        val otherPackage = ComponentName("com.example.clock", "com.example.ClockActivity")

        assertNull(SamsungDynamicIconSource.targetFor("Google", samsungClock))
        assertNull(SamsungDynamicIconSource.targetFor("Samsung", otherPackage))
    }

    @Test
    fun loadUsesInjectedLoaderWithExactTargetAndDensity() = runTest {
        val component = ComponentName(
            "com.samsung.android.calendar",
            "com.samsung.android.calendar.CalendarActivity",
        )
        val target = requireNotNull(SamsungDynamicIconSource.targetFor("samsung", component))
        val expected = ColorDrawable(Color.MAGENTA)
        var receivedTarget: SamsungDynamicIconSource.Target? = null
        var receivedDensity = 0
        val source = SamsungDynamicIconSource(target) { _, actualTarget, density ->
            receivedTarget = actualTarget
            receivedDensity = density
            expected
        }

        val actual = source.load(RuntimeEnvironment.getApplication(), density = 480)

        assertSame(expected, actual)
        assertEquals(target, receivedTarget)
        assertEquals(480, receivedDensity)
    }

    @Test
    fun loadReturnsNullForInvalidTargetWithoutCallingLoader() = runTest {
        val component = ComponentName("com.example.clock", "com.example.ClockActivity")
        val invalidTarget = SamsungDynamicIconSource.Target(
            kind = SamsungDynamicIconSource.Kind.CLOCK,
            packageName = component.packageName,
            component = component,
        )
        var called = false
        val source = SamsungDynamicIconSource(invalidTarget) { _, _, _ ->
            called = true
            ColorDrawable(Color.MAGENTA)
        }

        assertNull(source.load(RuntimeEnvironment.getApplication(), density = 0))
        assertEquals(false, called)
    }

    @Test
    fun loadConvertsRecoverableLoaderFailureToNull() = runTest {
        val component = ComponentName(
            "com.sec.android.app.clockpackage",
            "com.sec.android.app.clockpackage.ClockActivity",
        )
        val target = requireNotNull(SamsungDynamicIconSource.targetFor("samsung", component))
        val source = SamsungDynamicIconSource(target) { _, _, _ ->
            throw SecurityException("launcher service unavailable")
        }

        assertNull(source.load(RuntimeEnvironment.getApplication(), density = 0))
    }

    @Test
    fun loadRethrowsCancellation() = runTest {
        val component = ComponentName(
            "com.samsung.android.calendar",
            "com.samsung.android.calendar.CalendarActivity",
        )
        val target = requireNotNull(SamsungDynamicIconSource.targetFor("samsung", component))
        val cancellation = CancellationException("superseded")
        val source = SamsungDynamicIconSource(target) { _, _, _ ->
            throw cancellation
        }

        try {
            source.load(RuntimeEnvironment.getApplication(), density = 0)
            fail("CancellationException should be rethrown")
        } catch (caught: CancellationException) {
            // withContext may recover the stack trace by copying the cancellation instance.
            assertEquals(cancellation.message, caught.message)
        }
    }
}
