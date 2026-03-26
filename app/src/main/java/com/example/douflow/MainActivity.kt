package com.example.douflow

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.douflow.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity
 *
 * 职责：
 * 1. 引导用户开启无障碍服务（一次性设置）
 * 2. 运行时申请麦克风权限
 * 3. 启动/停止 VoiceCommandService
 * 4. 显示当前服务运行状态
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 接收 VoiceCommandService 的日志广播，显示到界面
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(VoiceCommandService.EXTRA_LOG) ?: return
            appendLog(msg)
        }
    }

    // 麦克风权限申请回调
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceService()
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用语音功能", Toast.LENGTH_LONG).show()
        }
    }

    // 无障碍服务设置页返回回调
    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateUI()

        // 注册日志广播
        val filter = IntentFilter(VoiceCommandService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setupUI() {
        binding.btnToggleService.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                showAccessibilityGuideDialog()
                return@setOnClickListener
            }
            if (isServiceRunning) {
                stopVoiceService()
            } else {
                checkMicPermissionAndStart()
            }
        }

        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
        }
    }

    private fun updateUI() {
        val a11yEnabled = isAccessibilityServiceEnabled()
        isServiceRunning = a11yEnabled && isVoiceServiceRunning()

        binding.tvA11yStatus.text = if (a11yEnabled) "无障碍服务：已开启 ✓" else "无障碍服务：未开启"
        binding.tvServiceStatus.text = if (isServiceRunning) "语音监听：运行中" else "语音监听：已停止"
        binding.btnToggleService.text = if (isServiceRunning) "停止语音监听" else "启动语音监听"
        binding.btnOpenAccessibility.isEnabled = !a11yEnabled
    }

    // -------------------------------------------------------------------------
    // 无障碍服务检测
    // -------------------------------------------------------------------------

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val expectedServiceId = "$packageName/${DouyinAccessibilityService::class.java.name}"
        return enabledServices.any { serviceInfo ->
            serviceInfo.resolveInfo?.serviceInfo?.let { resolvedService ->
                "${resolvedService.packageName}/${resolvedService.name}" == expectedServiceId
            } == true
        }
    }

    private fun showAccessibilityGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要开启无障碍服务")
            .setMessage(
                "本应用需要通过无障碍服务来模拟手势滑动抖音视频。\n\n" +
                "请在接下来的设置页面中找到语音滑动抖音并开启。"
            )
            .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
    }

    // -------------------------------------------------------------------------
    // VoiceCommandService 管理
    // -------------------------------------------------------------------------

    private fun isVoiceServiceRunning(): Boolean {
        // 简单通过共享标志判断（生产环境可用 ActivityManager 或 LiveData）
        return VoiceServiceState.isRunning
    }

    private fun checkMicPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> startVoiceService()

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                AlertDialog.Builder(this)
                    .setTitle("需要麦克风权限")
                    .setMessage("语音识别需要使用麦克风录音，请授予权限。")
                    .setPositiveButton("授权") { _, _ ->
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        VoiceServiceState.isRunning = true
        updateUI()
        appendLog("[系统] 语音监听服务已启动")
    }

    private fun stopVoiceService() {
        stopService(Intent(this, VoiceCommandService::class.java))
        VoiceServiceState.isRunning = false
        updateUI()
        appendLog("[系统] 语音监听服务已停止")
    }

    private fun appendLog(msg: String) {
        val time = timeFmt.format(Date())
        val line = "$time $msg\n"
        binding.tvLog.append(line)
        // 自动滚动到底部
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}

/** 简单的全局服务状态标志（供 Activity 读取） */
object VoiceServiceState {
    var isRunning = false
}
