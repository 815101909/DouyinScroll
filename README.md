# DouFlow

DouFlow 是一个基于 Android 的语音控制实验项目，用实时语音识别监听简单口令，再通过无障碍服务注入滑动手势，实现对抖音 / TikTok 视频流的免手动切换。

项目当前实现的是一条很直接的链路：

`麦克风录音 -> 阿里云语音 SDK 实时识别 -> 命中指令 -> 发送广播 -> 无障碍服务执行上下滑动`

当前仓库只保留实际应用代码，核心实现位于 `app/` 模块。

## 功能概览

- 前台服务常驻监听语音指令
- 使用阿里云语音 SDK 做实时识别
- 通过 `AccessibilityService` 模拟上下滑动
- 支持抖音国内版和 TikTok 包名识别
- 主界面可查看服务状态和实时日志

## 当前支持的语音指令

### 下一条

- 下一个
- 下一条
- 下一页
- `next`
- 跳过

### 上一条

- 上一个
- 上一条
- 上一页
- `previous`
- 返回

识别文本会先做小写化和常见标点清洗，再进行关键词匹配。

## 项目结构

```text
.
├─ app/
│  ├─ libs/
│  │  ├─ nuisdk-release.aar
│  │  └─ nuisdk-classes.jar
│  └─ src/main/
│     ├─ java/com/example/douflow/
│     │  ├─ MainActivity.kt
│     │  ├─ VoiceCommandService.kt
│     │  ├─ DouyinAccessibilityService.kt
│     │  └─ VoiceSdkConfig.kt
│     ├─ res/layout/activity_main.xml
│     └─ AndroidManifest.xml
```

## 核心组件

### `MainActivity`

- 引导用户开启无障碍服务
- 申请麦克风权限
- 启动 / 停止语音监听前台服务
- 展示运行状态与日志

### `VoiceCommandService`

- 初始化语音 SDK
- 维护前台服务与通知
- 从麦克风持续读取 PCM 音频
- 接收实时识别结果并解析口令
- 将“上一条 / 下一条”转换为广播指令

### `DouyinAccessibilityService`

- 监听来自 `VoiceCommandService` 的广播
- 调用 `dispatchGesture()` 注入上下滑动手势
- 识别抖音 / TikTok 是否在前台

## 开发环境

- Android Studio
- JDK 17
- Android SDK 34
- 最低 Android 版本：Android 7.0（API 24）

Gradle 配置可见于 `app/build.gradle.kts`：

- `compileSdk = 34`
- `targetSdk = 34`
- `minSdk = 24`

## 依赖说明

项目除了常规 AndroidX / Material 依赖外，还依赖本地语音 SDK 文件：

- `app/libs/nuisdk-release.aar`
- `app/libs/nuisdk-classes.jar`

这些依赖通过 `flatDir` 和本地文件方式接入，不需要从远程 Maven 仓库下载。

## 配置语音 SDK

语音 SDK 配置通过 `local.properties` 或 Gradle 属性注入到 `BuildConfig`：

```properties
voice.sdk.apiKey=YOUR_API_KEY
voice.sdk.url=wss://dashscope.aliyuncs.com/api-ws/v1/inference
voice.sdk.model=fun-asr-realtime
```

推荐写到项目根目录的 `local.properties` 中，例如local.properties.example：

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
voice.sdk.apiKey=YOUR_API_KEY
voice.sdk.url=wss://dashscope.aliyuncs.com/api-ws/v1/inference
voice.sdk.model=fun-asr-realtime
```

说明：

- `local.properties` 已被 `.gitignore` 忽略，不应提交真实密钥
- `voice.sdk.apiKey` 为空时，服务会直接停止并在日志中提示
- `voice.sdk.url` 与 `voice.sdk.model` 都支持覆盖，未配置时会使用默认值

如果你不想把密钥写入本地文件，也可以用 Gradle 属性传入：

```powershell
.\gradlew.bat assembleDebug -Pvoice.sdk.apiKey=YOUR_API_KEY
```

## 运行步骤

1. 使用 Android Studio 打开项目根目录。
2. 等待 Gradle Sync 完成。
3. 确认 `app/libs/` 下的语音 SDK 文件存在。
4. 在 `local.properties` 中配置 `voice.sdk.apiKey`。
5. 连接真机并运行 `app` 模块。
6. 首次启动后授予麦克风权限。
7. 按界面提示进入系统设置，开启无障碍服务“语音滑动抖音”。
8. 返回应用，点击“启动语音监听”。
9. 打开抖音或 TikTok，说出“下一个”“上一条”等指令进行测试。

## 需要的系统权限

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `INTERNET`
- `VIBRATE`
- 无障碍服务绑定权限 `BIND_ACCESSIBILITY_SERVICE`

## 工作原理

1. `VoiceCommandService` 启动后初始化阿里云语音 SDK。
2. SDK 通过回调持续向服务请求麦克风音频。
3. 服务收到部分识别和最终识别结果后，提取文本。
4. 若命中“下一条”或“上一条”关键词，则发送对应广播。
5. `DouyinAccessibilityService` 收到广播后执行向上或向下滑动手势。

## 注意事项

- 这是一个偏实验性质的自动化项目，识别效果依赖网络、麦克风环境和 SDK 配置。
- 前台服务必须保持运行，否则系统可能回收语音监听进程。
- 无障碍服务是实现滑动注入的关键，没有该权限无法控制抖音 / TikTok。
- 项目当前使用的是关键词匹配，不是复杂语义理解；如果需要更多口令，可直接扩展 `VoiceCommandService.kt` 中的关键词集合。
- 真机调试体验通常明显好于模拟器，尤其是麦克风和无障碍手势相关能力。

## 参考来源

- `app/src/main/java/com/example/douflow/`：本项目实际业务实现

## 后续可扩展方向

- 增加“暂停 / 继续 / 点赞 / 收藏”等更多语音指令
- 为不同 App 定制不同手势策略
- 增加前台状态校验，避免误操作其他应用
- 引入唤醒词，减少误触发
- 增加指令防抖和置信度过滤
