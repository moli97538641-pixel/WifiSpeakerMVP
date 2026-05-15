# WiFi Speaker MVP v0.3.5

## v0.3.5 Windows 图形界面

本版本的 Windows 端为图形界面程序，运行 `WifiSpeaker.Win.exe` 后会直接打开窗口。

Windows 界面包含：

- 接收端页面：启动 / 停止接收端、显示本机 IP 和运行状态。
- 发送端页面：搜索接收端设备、列表勾选多个设备、手动输入多个 IP、启动 / 停止一对多推送。
- 接收端音量滑条：由发送端控制接收端应用内播放音量，不修改发送端系统音量。
- 日志窗口：显示搜索、连接、推送、接收和错误信息。

Windows 端仍使用 WSPK0003 协议，音频帧带 sequence 和 presentationTimeNs，为后续低延迟和多设备同步继续打基础。


WiFi Speaker MVP 用于让多台设备在同一 Wi-Fi / 热点网络下进行局域网音频推送。

v0.3.5 的重点是：**Android / Windows 跨平台一对多推送 + 低延迟时间戳音频帧**。

## 支持能力

| 平台 | 发送端 | 接收端 |
|---|---:|---:|
| Android | 支持 | 支持 |
| Windows x64 | 支持 | 支持 |

## v0.3.5 新增内容

- Android 发送端支持一对多推送。
- 新增 Windows x64 命令行端。
- Windows 可作为接收端播放 Android / Windows 发送端推送的音频。
- Windows 可作为发送端，通过 WASAPI loopback 采集系统播放音频并推送到多个接收端。
- 协议升级为 `WSPK0003`。
- 音频流从“裸 PCM 流”升级为“带包头的 10ms 时间戳音频帧”。
- 音频帧包含：sequence、presentationTimeNs、durationFrames、payloadSize。
- Android 接收端使用更小的 AudioTrack buffer，并启用低延迟性能模式。
- Android / Windows 发送端均开启 TCP_NODELAY。
- 接收端会在缓冲积压过大时丢弃过期帧，优先降低延迟。
- 发送端音量滑条仍然控制所有已选择接收端的应用内播放音量。

## 端口

```text
45777 TCP: 音频流
45778 UDP: 设备发现
45779 TCP: 控制命令，例如接收端应用内音量
```

## Android 使用方式

1. 两台或多台设备连接同一个 Wi-Fi / 热点。
2. 接收端设备打开 Android App，选择“接收端 / 播放音频”，启动接收端。
3. 发送端设备打开 Android App，选择“发送端 / 推送音频”。
4. 点击“搜索接收端设备”。
5. 在列表里选择一个或多个接收端。
6. 点击启动推送。
7. 首次推送时允许录音权限和系统投屏 / 录制授权。

Android 发送端需要 Android 10 / API 29 或以上。

## Windows 使用方式

GitHub Actions 会生成 Windows artifact：

```text
WifiSpeakerMVP-v0.3.5-windows-x64
```

解压后运行：

```powershell
# Windows 作为接收端
.\WifiSpeaker.Win.exe receiver

# 搜索局域网接收端
.\WifiSpeaker.Win.exe discover

# Windows 作为发送端，一对多推送
.\WifiSpeaker.Win.exe send 192.168.1.35,192.168.1.36

# 调整接收端应用内音量
.\WifiSpeaker.Win.exe volume 60 192.168.1.35,192.168.1.36
```

Windows 发送端使用 WASAPI loopback 采集系统播放音频。

## 构建

### Android APK

GitHub Actions 工作流：

```text
Build Android APK
```

成功后下载 artifact：

```text
WifiSpeakerMVP-v0.3.5-debug-apk
```

解压得到 `app-debug.apk`。

### Windows x64

GitHub Actions 工作流：

```text
Build Windows App
```

成功后下载 artifact：

```text
WifiSpeakerMVP-v0.3.5-windows-x64
```

## 延迟说明

v0.3.5 已做低延迟优化：

- 10ms 音频帧。
- TCP_NODELAY。
- 更小的发送端队列。
- 更小的接收端播放缓冲。
- AudioTrack 低延迟性能模式。
- 接收端缓冲积压时丢弃旧帧。

但端到端延迟仍会受这些因素影响：

- Android 系统音频采集缓冲。
- Windows WASAPI loopback 事件周期。
- Wi-Fi / 热点调度。
- 接收端系统音频输出管线。
- 蓝牙耳机或外接音箱自身延迟。

当前的时间戳同步属于协议基础能力，用于低延迟缓冲和后续跨设备同步扩展。它还不是严格的 PTP/NTP 级多设备时钟同步。