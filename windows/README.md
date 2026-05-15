# WifiSpeakerMVP Windows GUI v0.3.5

Windows 端从 v0.3.5 开始提供图形界面，不再要求用户通过命令行操作。

## 功能

- Windows 作为接收端：接收 Android / Windows 发送端推送的音频并播放。
- Windows 作为发送端：使用 WASAPI Loopback 采集本机系统播放音频。
- 支持一对多推送：一个 Windows 发送端可同时推送到多个 Android / Windows 接收端。
- 支持搜索局域网接收端并以列表展示。
- 支持手动输入多个接收端 IP。
- 支持发送端调节所有已选接收端的应用内播放音量。
- 音频传输使用 WSPK0003 协议，10ms PCM16 stereo 帧，带 sequence 和 presentationTimeNs。

## 使用方法

1. 在一台设备上打开 Windows 程序，切换到“接收端”，点击“启动接收端”。
2. 在另一台设备上打开 Windows 程序或 Android App，作为发送端搜索接收端。
3. 勾选一个或多个接收端。
4. 点击“启动推送”。
5. 在发送端播放音乐或视频。

## Windows 权限提示

如果搜索不到设备或无法连接，请检查 Windows 防火墙，允许 `WifiSpeaker.Win.exe` 访问专用网络。

## 构建

GitHub Actions 中使用：

```powershell
dotnet publish windows/WifiSpeaker.Win/WifiSpeaker.Win.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o out/win-x64
```
