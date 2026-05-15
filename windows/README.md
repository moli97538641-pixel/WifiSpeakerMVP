# WifiSpeaker Windows GUI v0.3.6

Windows 端为图形界面程序，运行 `WifiSpeaker.Win.exe` 后直接打开窗口。

## 功能

- Windows 作为接收端，播放 Android / Windows 发送端推送的音频。
- Windows 作为发送端，通过 WASAPI loopback 采集系统播放音频。
- 搜索局域网接收端设备。
- 勾选多个接收端，实现一对多推送。
- 支持手动输入多个 IP。
- 支持通过滑条控制接收端应用内音量。
- v0.3.6 修复界面内容增多时的重叠问题，发送端页面支持滚动。

## 使用

1. 解压 GitHub Actions 生成的 `WifiSpeakerMVP-v0.3.6-windows-x64`。
2. 运行 `WifiSpeaker.Win.exe`。
3. 在“接收端”页面可启动 Windows 接收端。
4. 在“发送端”页面可搜索并勾选一个或多个接收端，然后启动推送。

如果 Windows 防火墙弹窗，请允许专用网络访问。
