# DouFlow

DouFlow 是一个基于 Android 的语音控制实验项目。

当前版本使用本地 `Sherpa-ONNX` 流式语音识别，持续监听短口令，再通过 `AccessibilityService` 注入手势，实现对抖音 / TikTok 视频流的免手动切换。

## 当前链路

`麦克风录音 -> Sherpa-ONNX 流式识别 -> 命中指令 -> 发送广播 -> 无障碍服务执行滑动或点击`

## 当前支持的语音指令

- 下一条：`下一个`、`下一条`、`下一页`、`next`、`跳过`
- 上一条：`上一个`、`上一条`、`上一页`、`previous`、`返回`
- 暂停 / 播放：`暂停`、`pause`、`点击`

## 主要模块

- `MainActivity`
  负责权限申请、无障碍入口、服务启停和日志展示。
- `VoiceCommandService`
  使用 Sherpa-ONNX 的 `OnlineRecognizer` 进行流式识别，并把命中的口令转换成广播。
- `DouyinAccessibilityService`
  接收广播后执行上滑、下滑或中心点击。

## 模型与依赖

- Sherpa Android AAR：`app/libs/sherpa-onnx-1.12.20.aar`
- 中文流式模型目录：`app/src/main/assets/models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/`

当前保留的模型文件：

- `encoder-epoch-99-avg-1.int8.onnx`
- `decoder-epoch-99-avg-1.int8.onnx`
- `joiner-epoch-99-avg-1.int8.onnx`
- `tokens.txt`

## 本地配置

在 `local.properties` 中保留：

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
sherpa.model.dir=models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23
```

## 运行步骤

1. 用 Android Studio 打开项目。
2. 等待 Gradle Sync 完成。
3. 连接真机并运行 `app` 模块。
4. 首次启动后授予麦克风权限。
5. 按界面提示开启无障碍服务“语音滑动抖音”。
6. 返回应用，启动语音监听。
7. 打开抖音或 TikTok，说出“下一个”“上一条”“暂停”等指令测试。

## 需要的系统权限

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- 无障碍服务绑定权限：`BIND_ACCESSIBILITY_SERVICE`

## 说明

- 当前方案以“短口令低延迟”为主，优先保证下一条 / 上一条 / 暂停这类命令的响应速度。
- 真机体验通常明显好于模拟器，尤其是麦克风和无障碍手势相关能力。
- 如果后面要扩展更多命令，优先修改 `VoiceCommandService.kt` 中的关键词集合即可。
