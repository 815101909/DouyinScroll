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
import androidx.core.content.ContextCompat

/**
 * DouyinAccessibilityService
 *
 * 核心职责：
 * 1. 监听来自 VoiceCommandService 的广播（ACTION_SWIPE_UP / ACTION_SWIPE_DOWN）
 * 2. 通过 dispatchGesture() 模拟手指滑动，切换抖音视频
 *
 * 工作原理：
 * AccessibilityService 运行在独立进程，拥有 canPerformGestures 权限后
 * 可以向任意 App 注入触摸手势，无需 Root。
 */
class DouyinAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SWIPE_UP = "com.example.douflow.SWIPE_UP"     // 下一个视频
        const val ACTION_SWIPE_DOWN = "com.example.douflow.SWIPE_DOWN" // 上一个视频
        const val TAG = "DouyinA11yService"

        // 抖音国内版 & 国际版包名
        val DOUYIN_PACKAGES = setOf(
            "com.ss.android.ugc.aweme",   // 抖音
            "com.zhiliaoapp.musically"    // TikTok
        )
    }

    private var isDouyinForeground = false

    // 广播接收器：接收来自 VoiceCommandService 的滑动指令
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SWIPE_UP -> performSwipe(direction = SwipeDirection.UP)
                ACTION_SWIPE_DOWN -> performSwipe(direction = SwipeDirection.DOWN)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")

        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_SWIPE_UP)
            addAction(ACTION_SWIPE_DOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 检测抖音是否在前台（可选：用于 UI 提示）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isDouyinForeground = event.packageName?.toString() in DOUYIN_PACKAGES
            Log.d(TAG, "抖音前台状态: $isDouyinForeground, 包名: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commandReceiver)
    }

    // -------------------------------------------------------------------------
    // 核心手势模拟逻辑
    // -------------------------------------------------------------------------

    enum class SwipeDirection { UP, DOWN }

    /**
     * 执行滑动手势
     *
     * 抖音视频切换原理：手指从屏幕中下部向上滑动 → 进入下一个视频
     *                    手指从屏幕中上部向下滑动 → 回到上一个视频
     *
     * @param direction UP=下一个视频, DOWN=上一个视频
     */
    private fun performSwipe(direction: SwipeDirection) {
        // 暂时移除前台检查，先验证手势链路是否通畅
        // if (!isDouyinForeground) {
        //     Log.w(TAG, "抖音不在前台，跳过滑动")
        //     return
        // }

        // 获取屏幕尺寸
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val centerX = screenWidth / 2f

        // 滑动起止点：使用屏幕中心区域，避免触碰边缘导致系统手势
        val (startY, endY) = when (direction) {
            SwipeDirection.UP -> Pair(screenHeight * 0.7f, screenHeight * 0.3f)   // 向上划
            SwipeDirection.DOWN -> Pair(screenHeight * 0.3f, screenHeight * 0.7f) // 向下划
        }

        val swipePath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        // StrokeDescription(path, startTime, duration)
        // duration 建议 300-500ms，太快抖音可能识别为误触
        val stroke = StrokeDescription(swipePath, 0L, 400L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "滑动完成: $direction")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "滑动被取消: $direction")
            }
        }, null)

        Log.d(TAG, "手势派发结果: $dispatched, 方向: $direction")
    }
}
