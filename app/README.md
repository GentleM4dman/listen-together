# 一起听 · 客户端

Kotlin Multiplatform 客户端，配合 [服务端](../server/)使用。

## 结构

| 模块 | 内容 |
| --- | --- |
| `shared` | 不含界面的共享逻辑：`/api/v1` 接口、WebSocket 长连接（hello、请求/ack、广播、断线重连）、NTP 式对时、房间状态、播放协调与纠偏 |
| `composeApp` | Compose Multiplatform 界面（加入、房间列表、房间、点歌、同步调试）；`androidMain` 里是 Media3 播放器和后台播放服务 |

播放规则：服务器给出开播时刻 `startAt`，每台手机按「服务器时间 − startAt」计算应在的位置。误差在 60–400ms 用 0.97/1.03 倍速追，超过 400ms 直接跳转。音频先直连 CDN，出错自动改走服务器代理。

## 构建

```bash
./gradlew :shared:jvmTest              # 共享逻辑单元测试
./gradlew :composeApp:assembleDebug    # Android 调试包
```

需要 JDK 21 和 Android SDK 36（可用 Android Studio 配置）。iOS 目标要装 Xcode 后才能编译，播放器（AVPlayer）和 iOS 入口还没写。

## 第三方代码

逐字歌词动画移植自 [aura-music](https://github.com/dingyi222666/aura-music)，见[版权及 MIT 许可声明](third_party/licenses/aura-music-MIT.txt)。
