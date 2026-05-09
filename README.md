# WiFi Speaker MVP

一个最小可运行的 Android 局域网音箱示例：

- 平板：启动接收端，监听 TCP `45777`，收到 PCM 后用 `AudioTrack` 播放。
- 手机/另一台 Android：启动发送端，使用 `MediaProjection + AudioPlaybackCaptureConfiguration + AudioRecord` 采集本机播放音频，再通过 Wi-Fi TCP 推到平板。

## 音频格式

当前 MVP 使用未压缩 PCM：

- 采样率：48 kHz
- 位深：16 bit
- 声道：stereo
- 带宽：约 1.536 Mbps，不含 TCP/IP 开销

这不是蓝牙 A2DP，而是 Wi-Fi 直传。音质优先，延迟取决于 Wi-Fi、系统缓冲和 AudioTrack 缓冲。

## 构建

用 Android Studio 打开本目录，然后运行 `:app`。

工程参数：

- minSdk: 29，因为系统播放音频采集需要 Android 10+
- targetSdk / compileSdk: 35
- 语言：Java，无第三方依赖

## 使用方法

1. 两台设备连接到同一个 Wi-Fi。
2. 两台设备都安装这个 App。
3. 在平板上打开 App，点击“平板：启动接收端”。
4. 记下平板界面显示的 IP 地址。
5. 在发送端 Android 设备上打开 App，输入平板 IP。
6. 点击“手机：启动发送端”。
7. 系统会弹出投屏/录制授权，确认后开始推流。
8. 在发送端播放音乐或视频，声音会从平板播放。

## 重要限制

- 发送端需要 Android 10/API 29 以上。
- 只能采集允许被系统录制的 App 音频。
- DRM、通话、受保护内容、某些播放器或主动禁止 capture 的 App 可能采不到。
- 当前版本没有做自动发现、重连、抖动缓冲、音量同步、Opus/FLAC 编码。

## 后续可加功能

- mDNS/NSD 自动发现平板，不用手动输入 IP。
- UDP/RTP + jitter buffer，降低延迟并提升抗抖动能力。
- Opus 编码，降低带宽并适合弱网。
- FLAC/ALAC 编码，降低带宽且保持无损。
- 双端时间戳和动态缓冲控制。
- 音量同步、连接状态 UI、断线自动重连。
