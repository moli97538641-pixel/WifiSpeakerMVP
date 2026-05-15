# WiFi Speaker MVP v0.3.6

WiFi Speaker MVP 用于让 Android / Windows 设备在同一 Wi-Fi 或热点网络下进行局域网音频推送。

v0.3.6 基于 v0.3.5 修改，重点不是增加新平台，而是修复两个实际问题：

- 多个接收端同时连接时，个别接收端延迟明显偏大。
- 为了追低延迟导致播放缓冲过低，出现声音断断续续。
- Windows 图形界面内容增加后发生控件重叠。

## 支持能力

| 平台 | 发送端 | 接收端 |
|---|---:|---:|
| Android | 支持 | 支持 |
| Windows x64 | 支持 | 支持 |

## v0.3.6 更新内容

- Android / Windows 发送端在开始采集前会短暂等待多个接收端完成初始连接，减少不同接收端起播时间差。
- Android 接收端增加约 180ms 初始预缓冲，避免刚开始播放就吃空导致断续。
- Windows 接收端增加约 180ms 初始预缓冲，`WaveOut` 不再一收到数据就立刻播放。
- Android / Windows 接收端改为“目标缓冲 + 过高积压再丢帧”的策略。
- 一对多时保留每个接收端独立发送队列，慢设备队列满时只丢最旧帧，不清空整个队列。
- Windows 图形界面改为可滚动布局，发送端页面内容变多后不再重叠。
- 保留 v0.3.5 的 Android / Windows 一对多、设备搜索、接收端音量控制、10ms 音频帧和 Windows 图形端。

## 同步策略说明

v0.3.6 解决的是“接收端之间的相对同步和连续性问题”，不是 PTP/NTP 级严格时钟同步。

这一版采用更稳的工程策略：

```text
发送端：
  等待多个接收端初始连接
  每个接收端独立队列
  队列满时丢旧帧，保留最新音频

接收端：
  先预缓冲约 180ms 再播放
  缓冲正常时保持连续播放
  缓冲明显过高时丢旧帧追同步
  缓冲严重积压时重置播放缓冲
```

这样比上一版极限低延迟策略更稳定，目标是减少“一台慢半拍”和“声音断续”。

## 端口

```text
45777 TCP: 音频流
45778 UDP: 设备发现
45779 TCP: 控制命令，例如接收端应用内音量
```

## Android 使用方式

1. 多台设备连接同一个 Wi-Fi 或热点。
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
WifiSpeakerMVP-v0.3.6-windows-x64
```

解压后直接运行：

```text
WifiSpeaker.Win.exe
```

Windows 图形端包含：

- 接收端页面：启动 / 停止接收端，显示本机 IP、端口和状态。
- 发送端页面：搜索接收端设备、勾选多个设备、手动输入多个 IP、启动 / 停止推送。
- 接收端音量滑条：控制所有已选接收端的应用内播放音量，不修改发送端系统音量。
- 日志窗口：显示搜索、连接、推送、接收和错误信息。

如果 Windows 防火墙弹窗，请允许专用网络访问，否则设备发现或连接可能失败。

## 构建

### Android APK

GitHub Actions 工作流：

```text
Build Android APK
```

成功后下载 artifact：

```text
WifiSpeakerMVP-v0.3.6-debug-apk
```

解压得到 `app-debug.apk`。

### Windows x64

GitHub Actions 工作流：

```text
Build Windows App
```

成功后下载 artifact：

```text
WifiSpeakerMVP-v0.3.6-windows-x64
```

解压后运行 `WifiSpeaker.Win.exe`。

## 测试建议

建议按顺序测试：

1. Android 发送端 -> 1 个 Android 接收端。
2. Android 发送端 -> 2 个 Android 接收端。
3. Windows 发送端 -> 1 个 Windows 接收端。
4. Windows 发送端 -> Android + Windows 多接收端。
5. 再逐步增加接收端数量。

如果仍有某一端明显慢半拍，请记录：发送端平台、接收端平台、是否热点直连、接收端数量、是否使用蓝牙耳机。
