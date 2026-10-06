package com.fiilda.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** Android host view integration for provider touch negotiation and launcher drag handoff. */
/** Creates host views that can own a launcher long-press without changing provider touch targets. */
internal class LauncherAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo,
    ): AppWidgetHostView = LauncherAppWidgetHostView(context)
}

/** Inputs that affect an AppWidgetHostView's provider size negotiation. */
private data class AppWidgetHostSizeSignature(
    val provider: ComponentName?,
    val widthDp: Float,
    val heightDp: Float,
    val paddingLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
)

/**
 * AppWidgetHostView-level long-press gate.
 *
 * RemoteViews are ordinary Android child views, so a Compose pointer detector around AndroidView
 * cannot reliably cancel a provider click after the timeout. This view forwards every event to
 * the provider until a stationary, single-pointer timeout wins; then it sends the provider an
 * ACTION_CANCEL and owns the rest of that gesture. Movement and multi-pointer input continue to
 * the provider and cancel the launcher recognizer.
 */
internal class LauncherAppWidgetHostView(context: Context) : AppWidgetHostView(context) {
    private val viewConfiguration = ViewConfiguration.get(context)
    private val longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong()
    private var longPressRunnable: Runnable? = null
    private var latestEvent: MotionEvent? = null
    private var longPressPointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var longPressCanceled = false
    private var longPressAccepted = false
    private var gestureGeneration = Long.MIN_VALUE
    private var lastAppWidgetSizeSignature: AppWidgetHostSizeSignature? = null

    /** Returns true only when the shared launcher drag coordinator accepted this gesture. */
    var onWidgetLongPressAccepted: ((x: Float, y: Float, generation: Long) -> Boolean)? = null
    var onWidgetPointerMove: ((x: Float, y: Float) -> Unit)? = null
    var onWidgetPointerUp: ((x: Float, y: Float) -> Boolean)? = null
    var onWidgetGestureCancel: (() -> Unit)? = null
    var onWidgetGestureEnabled: (() -> Boolean)? = null
    var onWidgetGestureStarted: (() -> Long)? = null
    private var hostOwnsGestureAtDown = false

    /**
     * Negotiates a provider's RemoteViews size only when its effective inputs changed.
     *
     * AndroidView's update lambda can run for unrelated Compose state (clock/media/drag updates),
     * so issuing updateAppWidgetSize from that lambda unconditionally needlessly asks the provider
     * to rebuild RemoteViews. The signature includes provider identity, floating-point size, and
     * all host padding edges; a failed provider update is intentionally retried on the next pass.
     */
    fun updateAppWidgetSizeIfNeeded(
        provider: ComponentName?,
        options: Bundle,
        widthDp: Float,
        heightDp: Float,
        padding: Rect,
    ) {
        val signature = AppWidgetHostSizeSignature(
            provider = provider,
            widthDp = widthDp,
            heightDp = heightDp,
            paddingLeft = padding.left,
            paddingTop = padding.top,
            paddingRight = padding.right,
            paddingBottom = padding.bottom,
        )
        if (lastAppWidgetSizeSignature == signature) return

        runCatching {
            if (
                paddingLeft != padding.left ||
                paddingTop != padding.top ||
                paddingRight != padding.right ||
                paddingBottom != padding.bottom
            ) {
                setPadding(padding.left, padding.top, padding.right, padding.bottom)
            }
            val roundedWidthDp = widthDp.roundToInt()
            val roundedHeightDp = heightDp.roundToInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                updateAppWidgetSize(
                    options,
                    listOf(SizeF(widthDp, heightDp)),
                )
            } else {
                updateAppWidgetSize(
                    options,
                    roundedWidthDp,
                    roundedHeightDp,
                    roundedWidthDp,
                    roundedHeightDp,
                )
            }
        }.onSuccess {
            lastAppWidgetSizeSignature = signature
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            finishPreviousGesture()
            rememberEvent(event)
            longPressPointerId = event.getPointerId(0)
            downX = event.x
            downY = event.y
            downTime = event.downTime
            longPressCanceled = false
            longPressAccepted = false
            hostOwnsGestureAtDown = onWidgetGestureEnabled?.invoke() == true
            gestureGeneration = if (hostOwnsGestureAtDown) {
                onWidgetGestureStarted?.invoke() ?: Long.MIN_VALUE
            } else {
                Long.MIN_VALUE
            }

            // Always retain the host as the dispatch target, even if a provider has no clickable
            // root. The provider still receives the exact DOWN through the superclass dispatch.
            super.dispatchTouchEvent(event)
            scheduleLongPress()
            return true
        }

        if (longPressAccepted) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                cancelAcceptedWidgetGesture()
                finishAcceptedGesture()
                return true
            }
            if (event.pointerCount > 1) {
                cancelAcceptedWidgetGesture()
                return true
            }
            if (acceptedGestureCanceled) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    finishAcceptedGesture()
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val pointerIndex = event.findPointerIndex(longPressPointerId)
                if (pointerIndex < 0) {
                    cancelAcceptedWidgetGesture()
                } else {
                    onWidgetPointerMove?.invoke(
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                    )
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val pointerIndex = event.findPointerIndex(longPressPointerId)
                val didDrop = if (pointerIndex >= 0) {
                    onWidgetPointerUp?.invoke(
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                    ) ?: false
                } else {
                    onWidgetGestureCancel?.invoke()
                    false
                }
                finishAcceptedGesture()
                if (didDrop) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    onWidgetGestureCancel?.invoke()
                }
                finishAcceptedGesture()
            }
            // The provider already received ACTION_CANCEL when the launcher accepted the long
            // press. Do not let its child turn this release into a click.
            return true
        }

        rememberEvent(event)
        if (!longPressCanceled && event.pointerCount > 1) {
            cancelLongPressRecognition()
        }
        if (!longPressCanceled && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val pointerIndex = event.findPointerIndex(longPressPointerId)
            if (pointerIndex < 0) {
                cancelLongPressRecognition()
            } else {
                val dx = event.getX(pointerIndex) - downX
                val dy = event.getY(pointerIndex) - downY
                if (dx * dx + dy * dy >
                    viewConfiguration.scaledTouchSlop.toFloat() *
                    viewConfiguration.scaledTouchSlop.toFloat()
                ) {
                    cancelLongPressRecognition()
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            cancelLongPressRecognition()
        }

        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            finishPreviousGesture()
        }
        return handled
    }

    private fun scheduleLongPress() {
        val runnable = Runnable {
            if (!hostOwnsGestureAtDown ||
                longPressCanceled ||
                longPressAccepted ||
                longPressPointerId == MotionEvent.INVALID_POINTER_ID
            ) {
                return@Runnable
            }
            longPressAccepted = true
            parent?.requestDisallowInterceptTouchEvent(true)
            cancelProviderGesture()
            val pointerIndex = latestEvent?.findPointerIndex(longPressPointerId) ?: -1
            val x = latestEvent?.let { event ->
                if (pointerIndex >= 0) event.getX(pointerIndex) else downX
            } ?: downX
            val y = latestEvent?.let { event ->
                if (pointerIndex >= 0) event.getY(pointerIndex) else downY
            } ?: downY
            val accepted = onWidgetLongPressAccepted?.invoke(x, y, gestureGeneration) == true
            if (accepted) {
                // A rejected second-pane gesture still shields the provider from a click, but it
                // must not provide the launcher long-press acceptance cue.
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } else {
                acceptedGestureCanceled = true
            }
        }
        longPressRunnable = runnable
        postDelayed(runnable, longPressTimeoutMillis)
    }

    private fun cancelLongPressRecognition() {
        longPressCanceled = true
        longPressRunnable?.let(::removeCallbacks)
        longPressRunnable = null
    }

    private fun cancelProviderGesture() {
        val cancelEvent = latestEvent?.let(MotionEvent::obtain)
            ?: MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL,
                downX,
                downY,
                0,
            )
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }

    private fun rememberEvent(event: MotionEvent) {
        latestEvent?.recycle()
        latestEvent = MotionEvent.obtain(event)
    }

    private fun finishAcceptedGesture() {
        longPressRunnable?.let(::removeCallbacks)
        longPressRunnable = null
        latestEvent?.recycle()
        latestEvent = null
        longPressPointerId = MotionEvent.INVALID_POINTER_ID
        longPressAccepted = false
        acceptedGestureCanceled = false
        longPressCanceled = false
        hostOwnsGestureAtDown = false
        gestureGeneration = Long.MIN_VALUE
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private var acceptedGestureCanceled = false

    private fun cancelAcceptedWidgetGesture() {
        if (!acceptedGestureCanceled) {
            acceptedGestureCanceled = true
            onWidgetGestureCancel?.invoke()
        }
    }

    private fun finishPreviousGesture() {
        if (longPressAccepted) {
            cancelProviderGesture()
            cancelAcceptedWidgetGesture()
        }
        longPressRunnable?.let(::removeCallbacks)
        longPressRunnable = null
        latestEvent?.recycle()
        latestEvent = null
        longPressPointerId = MotionEvent.INVALID_POINTER_ID
        longPressCanceled = false
        longPressAccepted = false
        acceptedGestureCanceled = false
        hostOwnsGestureAtDown = false
        gestureGeneration = Long.MIN_VALUE
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onDetachedFromWindow() {
        finishPreviousGesture()
        super.onDetachedFromWindow()
    }
}

