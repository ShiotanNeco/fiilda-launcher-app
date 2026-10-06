package com.fiilda.launcher

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DynamicIconLifecycleTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayedSamsungResultCannotOverwriteReboundStaticIcon() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val container = FrameLayout(controller.get())
        controller.get().setContentView(container)
        val view = DynamicIconImageView(controller.get())
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val target = requireNotNull(SamsungDynamicIconSource.targetFor(
            "samsung", ComponentName("com.sec.android.app.clockpackage", "Clock"),
        ))
        // Emulate a binder/provider operation which returns after cancellation was requested.
        val source = SamsungDynamicIconSource(target) { _, _, _ ->
            withContext(NonCancellable) {
                started.complete(Unit)
                finish.await()
                returned.complete(Unit)
                ColorDrawable(Color.RED)
            }
        }
        try {
            view.bindApp(app(SamsungDynamicIconSpec(source, 320)))
            container.addView(view, FrameLayout.LayoutParams(100, 100))
            container.layout(0, 0, 500, 500)
            view.layout(0, 0, 100, 100)
            shadowOf(Looper.getMainLooper()).idle()
            showTestWindow(view)
            withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }
            assertEquals(Color.BLUE, (view.drawable as ColorDrawable).color)
            view.bindApp(app().copy(icon = ColorDrawable(Color.GREEN)))
            finish.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(5_000) { returned.await() } }
            runCurrent()
            assertEquals(Color.GREEN, (view.drawable as ColorDrawable).color)
        } finally {
            finish.complete(Unit)
            container.removeAllViews()
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failingVendorConstantStateFallsBackToIndependentSnapshot() {
        val source = object : ColorDrawable(Color.MAGENTA) {
            override fun getConstantState(): Drawable.ConstantState =
                object : Drawable.ConstantState() {
                    override fun newDrawable(): Drawable = throw Resources.NotFoundException()
                    override fun getChangingConfigurations() = 0
                }
        }
        val result = independentLauncherDrawable(source, RuntimeEnvironment.getApplication().resources)
        assertTrue(result is BitmapDrawable)
        assertEquals(Color.MAGENTA, (result as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    @Test
    fun packageRefreshReplacesStaticIconForSameComponent() {
        val view = DynamicIconImageView(RuntimeEnvironment.getApplication())
        val app = app()
        view.bindApp(app)
        view.bindApp(app.copy(icon = ColorDrawable(Color.GREEN)))
        assertEquals(Color.GREEN, (view.drawable as ColorDrawable).color)
    }

    @Test
    fun shortcutOverrideDoesNotUseOrTickAppDynamicIcon() {
        val spec = CountingSpec()
        val view = DynamicIconImageView(RuntimeEnvironment.getApplication())
        view.bindApp(app(spec), ColorDrawable(Color.GREEN))
        view.refreshDynamicIcon(1234L)
        assertEquals(0, spec.creations)
        assertEquals(0, spec.updates)
        assertEquals(Color.GREEN, (view.drawable as ColorDrawable).color)
    }

    @Test
    fun parentVisibilityStopsTicksAndReturningRefreshesImmediately() {
        withAttachedIcon { container, view, spec, _ ->
            assertTrue("attached=${view.isAttachedToWindow}, shown=${view.isShown}, window=${view.windowVisibility}", view.isTickerEligible())
            val before = spec.updates
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertTrue(spec.updates > before)
            container.visibility = View.GONE
            val hidden = spec.updates
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(hidden, spec.updates)
            container.visibility = View.VISIBLE
            assertTrue(spec.updates > hidden)
        }
    }

    @Test
    fun lifecycleStopAndDetachStopTicksAndStartRefreshesImmediately() {
        withAttachedIcon { container, view, spec, owner ->
            owner.registry.currentState = Lifecycle.State.CREATED
            val stopped = spec.updates
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(stopped, spec.updates)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(spec.updates > stopped)
            container.removeView(view)
            val detached = spec.updates
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(detached, spec.updates)
            assertFalse(view.isTickerEligible())
        }
    }

    @Test
    fun failedDateReplacementKeepsOldIconAndRetries() {
        val spec = object : CountingSpec() {
            var fail = false
            override fun stateKey(atMillis: Long) = atMillis
            override fun newDrawable(context: Context, atMillis: Long): Drawable? =
                if (fail) null else super.newDrawable(context, atMillis)
        }
        val view = DynamicIconImageView(RuntimeEnvironment.getApplication())
        view.bindApp(app(spec))
        spec.fail = true
        view.refreshDynamicIcon(1L)
        assertEquals(1, spec.creations)
        spec.fail = false
        view.refreshDynamicIcon(1L)
        assertEquals(2, spec.creations)
    }

    private fun withAttachedIcon(
        check: (FrameLayout, DynamicIconImageView, CountingSpec, Owner) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val container = FrameLayout(controller.get())
        val owner = Owner()
        container.setViewTreeLifecycleOwner(owner)
        owner.registry.currentState = Lifecycle.State.STARTED
        controller.get().setContentView(container)
        val spec = CountingSpec()
        val view = DynamicIconImageView(controller.get())
        view.bindApp(app(spec))
        container.addView(view, FrameLayout.LayoutParams(100, 100))
        container.layout(0, 0, 500, 500)
        view.layout(0, 0, 100, 100)
        shadowOf(Looper.getMainLooper()).idle()
        showTestWindow(view)
        try {
            check(container, view, spec, owner)
        } finally {
            container.removeAllViews()
            owner.registry.currentState = Lifecycle.State.DESTROYED
            controller.pause().stop().destroy()
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun showTestWindow(view: View) {
        // Generic test Activities do not receive WindowManager's visible-window callback.
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
    }

    private open class CountingSpec : DynamicAppIconSpec() {
        var creations = 0
        var updates = 0
        override val updateIntervalMillis = DynamicIconSecondTickMillis
        override val hasSecondHand = true
        override fun stateKey(atMillis: Long) = 0L
        override fun newDrawable(context: Context, atMillis: Long): Drawable? {
            creations++
            return ColorDrawable(Color.RED)
        }
        override fun updateDrawable(context: Context, current: Drawable, atMillis: Long): Drawable? {
            updates++
            return null
        }
    }

    private fun app(spec: DynamicAppIconSpec? = null) = LaunchableApp(
        packageName = "test.clock",
        className = "test.clock.Main",
        label = "Clock",
        icon = ColorDrawable(Color.BLUE),
        tileColorArgb = Color.BLUE,
        tileContentColorArgb = Color.WHITE,
        dynamicIcon = spec,
    )
}
