# WiFi Speaker MVP

把一台 Android 平板当作 Wi-Fi 音箱，另一台 Android 设备把系统播放音频推送到平板播放。

## v0.2.0 新增

- 自动发现接收端：平板点“启动接收端”后，手机点“搜索平板”即可自动填 IP。
- 发送端断线重连：Wi-Fi 短暂断开、平板接收端重启后，发送端会继续尝试连接。
- 接收端同时启动 UDP 发现服务和 TCP 音频接收服务。

## 工作方式

```text
发送端 Android 10+
  -> MediaProjection 授权
  -> AudioPlaybackCaptureConfiguration 采集系统播放音频
  -> AudioRecord 得到 48kHz / 16bit / stereo PCM
  -> 局域网 TCP 推送

平板接收端
  -> UDP 45778 响应自动发现
  -> TCP 45777 接收 PCM
  -> AudioTrack 流式播放
```

## 使用步骤

1. 两台设备连同一个 Wi-Fi。
2. 平板打开 App，点“平板：启动接收端 / Speaker”。
3. 手机打开 App，点“搜索平板 / Auto discover”。
4. 搜到后会自动填入平板 IP。
5. 手机点“手机：启动发送端 / Push audio”。
6. 允许录音权限和系统投屏/录制授权。
7. 手机上播放普通音乐或视频，声音会从平板播放。

如果自动搜索失败，也可以手动输入平板界面显示的 IP。

## 注意事项

- 发送端需要 Android 10 / API 29 或更高版本。
- 某些 App 会禁止被系统音频采集，DRM、通话、受保护内容通常采不到。
- 自动发现依赖同一局域网 UDP 广播；访客网络、AP 隔离、部分路由器防火墙会导致搜不到。
- 当前版本仍是 MVP，音频未压缩 PCM 直传，音质优先，带宽约 1.5 Mbps。

## 不用 Android Studio 打包

本项目包含 GitHub Actions 配置。上传到 GitHub 仓库后，进入 Actions，运行 `Build Android APK`，构建成功后从 Artifacts 下载 `app-debug.apk`。
