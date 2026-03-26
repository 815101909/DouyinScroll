package com.example.douflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class DouyinAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SWIPE_UP = "com.example.douflow.SWIPE_UP"
        const val ACTION_SWIPE_DOWN = "com.example.douflow.SWIPE_DOWN"
        const val ACTION_TAP_CENTER = "com.example.douflow.TAP_CENTER"
        const val TAG = "DouyinA11yService"

        val DOUYIN_PACKAGES = setOf(
            "com.ss.android.ugc.aweme",
            "com.zhiliaoapp.musically"
        )
    }

    private var isDouyinForeground = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SWIPE_UP -> performSwipe(direction = SwipeDirection.UP)
                ACTION_SWIPE_DOWN -> performSwipe(direction = SwipeDirection.DOWN)
                ACTION_TAP_CENTER -> performTapCenter()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        val filter = IntentFilter().apply {
            addAction(ACTION_SWIPE_UP)
            addAction(ACTION_SWIPE_DOWN)
            addAction(ACTION_TAP_CENTER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isDouyinForeground = event.packageName?.toString() in DOUYIN_PACKAGES
            Log.d(TAG, "Douyin foreground: $isDouyinForeground, package=${event.packageName}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commandReceiver)
    }

    enum class SwipeDirection { UP, DOWN }

    private fun performSwipe(direction: SwipeDirection) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val centerX = screenWidth / 2f

        val (startY, endY) = when (direction) {
            SwipeDirection.UP -> Pair(screenHeight * 0.7f, screenHeight * 0.3f)
            SwipeDirection.DOWN -> Pair(screenHeight * 0.3f, screenHeight * 0.7f)
        }

        val swipePath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = StrokeDescription(swipePath, 0L, 400L)
        dispatchGestureWithLog(
            gesture = GestureDescription.Builder().addStroke(stroke).build(),
            description = "swipe_$direction"
        )
    }

    private fun performTapCenter() {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        val tapPath = Path().apply {
            moveTo(centerX, centerY)
        }
        val tapStroke = StrokeDescription(tapPath, 0L, 80L)
        dispatchGestureWithLog(
            gesture = GestureDescription.Builder().addStroke(tapStroke).build(),
            description = "tap_center"
        )
    }

    private fun dispatchGestureWithLog(gesture: GestureDescription, description: String) {
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Gesture completed: $description")
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Gesture cancelled: $description")
            }
        }, null)

        Log.d(TAG, "Gesture dispatch result: $dispatched, action=$description")
    }
}
