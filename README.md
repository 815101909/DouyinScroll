# DouFlow

DouFlow 是一个 Android 小工具：  
用语音命令控制抖音 / TikTok 刷视频。

它的核心思路很简单：

1. 手机麦克风持续监听语音
2. 本地语音识别把你说的话转成文字
3. 命中指定口令后，应用发送动作指令
4. 无障碍服务在抖音 / TikTok 里执行滑动、点击、双击等手势

换句话说，这个项目就是：

**“用语音刷抖音”**

比如你可以直接说：

- “下一个”
- “上一条”
- “暂停”
- “双击”
- “点赞”
- “打开评论”

应用就会帮你执行对应操作。

## 这个项目能做什么

当前已经支持这些语音控制动作：

- 刷到下一个视频
- 返回上一个视频
- 点击屏幕中间，暂停 / 播放视频
- 双击屏幕中间
- 点赞
- 打开评论区

支持的目标 App：

- 抖音：`com.ss.android.ugc.aweme`
- TikTok：`com.zhiliaoapp.musically`

## 目前支持的语音指令

### 切到下一个视频

- 下一个
- 下一条
- 下一页
- next
- 跳过

### 回到上一个视频

- 上一个
- 上一条
- 上一页
- previous
- 返回

### 暂停 / 播放

- 暂停
- pause
- 点击

### 双击

- 双击
- 连击
- double tap
- double

### 点赞

- 点赞
- 喜欢
- like
- 爱心
- 点个赞

### 打开评论区

- 评论
- 打开评论
- 评论区
- 看评论
- comments

## 它是怎么工作的

当前项目使用的是：

- 本地 `Sherpa-ONNX` 流式语音识别
- Android 前台服务持续监听麦克风
- Android 无障碍服务执行手势

工作链路如下：

`麦克风录音 -> Sherpa-ONNX 实时识别 -> 命中口令 -> 发送广播 -> 无障碍服务操作抖音 / TikTok`

和云端语音识别不同，这一版主要是**本地运行**：

- 不依赖在线语音 API
- 不按调用次数收费
- 主要消耗的是手机 CPU、耗电和一点发热

## 项目里最重要的几个文件

### [app/src/main/java/com/example/douflow/MainActivity.kt](/d:/project/DOUYIN/app/src/main/java/com/example/douflow/MainActivity.kt)

主界面，负责：

- 申请麦克风权限
- 引导开启无障碍服务
- 启动 / 停止语音监听
- 显示实时日志

### [app/src/main/java/com/example/douflow/VoiceCommandService.kt](/d:/project/DOUYIN/app/src/main/java/com/example/douflow/VoiceCommandService.kt)

语音监听前台服务，负责：

- 初始化 Sherpa-ONNX
- 从麦克风读取音频
- 做流式语音识别
- 把识别结果匹配成“下一条 / 点赞 / 评论区”等命令

### [app/src/main/java/com/example/douflow/DouyinAccessibilityService.kt](/d:/project/DOUYIN/app/src/main/java/com/example/douflow/DouyinAccessibilityService.kt)

无障碍服务，负责：

- 监听语音服务发出的动作广播
- 在抖音 / TikTok 前台执行滑动和点击手势
- 防止在其它 App 里误触发

### [app/src/main/java/com/example/douflow/SherpaConfig.kt](/d:/project/DOUYIN/app/src/main/java/com/example/douflow/SherpaConfig.kt)

Sherpa-ONNX 的本地配置入口。

## 当前用到的本地模型

项目内已经使用中文流式模型：

- 模型目录：`app/src/main/assets/models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/`

保留的模型文件：

- `encoder-epoch-99-avg-1.int8.onnx`
- `decoder-epoch-99-avg-1.int8.onnx`
- `joiner-epoch-99-avg-1.int8.onnx`
- `tokens.txt`

Sherpa Android 依赖文件：

- `app/libs/sherpa-onnx-1.12.20.aar`

## 怎么运行

### 开发环境

- Android Studio
- JDK 17 或可兼容版本
- Android SDK 34
- 真机调试

### 本地配置

在 `local.properties` 里保留这一项：

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
sherpa.model.dir=models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23
```

示例文件见：

- [local.properties.example](/d:/project/DOUYIN/local.properties.example)

### 启动步骤

1. 用 Android Studio 打开项目
2. 等待 Gradle Sync 完成
3. 连接 Android 真机
4. 运行 `app` 模块
5. 首次启动后授予麦克风权限
6. 按界面提示开启无障碍服务“语音滑动抖音”
7. 返回应用，点击启动语音监听
8. 打开抖音或 TikTok
9. 说出“下一个”“点赞”“打开评论”等命令进行测试

## 需要的权限

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- 无障碍服务绑定权限：`BIND_ACCESSIBILITY_SERVICE`

## 使用时要知道的几点

- 这个项目的定位是“语音控制刷抖音 / TikTok”，不是通用语音助手
- 现在更偏向短命令场景，越短越直接，识别和响应通常越快
- “点赞”和“打开评论区”是通过固定屏幕区域点击实现的，不同机型或不同版本 UI 可能需要微调坐标
- 无障碍动作现在只会在抖音 / TikTok 前台执行，避免误操作别的 App
- 真机体验通常明显好于模拟器

## 当前适合的使用方式

最推荐的用法就是把它当成一个“免手点按”的短口令工具：

- 说“下一个”继续刷
- 说“上一条”返回
- 说“暂停”停住
- 说“点赞”点爱心
- 说“评论区”直接打开评论

## 后面还可以继续做什么

- 增加“收藏”“关注”“分享”等更多语音动作
- 给不同分辨率 / 机型单独调点赞和评论区坐标
- 增加更稳的评论区 / 点赞定位逻辑，而不是只靠固定坐标
- 增加防抖，避免一句话触发两次
- 做一个更直观的调试页，直接显示当前识别文本和命令命中结果
