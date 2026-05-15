# 不安装 Android Studio 构建 APK

本项目已经包含 GitHub Actions 工作流，可以在 GitHub 云端构建 debug APK。

## 更新源码

1. 打开 GitHub Desktop。
2. 选择 `WifiSpeakerMVP` 仓库。
3. 点击 `Repository -> Show in Explorer` 打开本地仓库目录。
4. 删除旧源码文件，但不要删除 `.git` 文件夹。
5. 解压新版 ZIP，把里面 `WifiSpeakerMVP` 文件夹中的内容复制到仓库根目录。
6. 在 GitHub Desktop 中提交：`Release v0.3.6`。
7. 点击 `Push origin`。

## 云端构建

1. 打开 GitHub 仓库网页。
2. 进入 `Actions -> Build Android APK`。
3. 等待最新构建变成绿色对勾。
4. 点进构建记录。
5. 在页面底部 `Artifacts` 下载 `WifiSpeakerMVP-v0.3.6-debug-apk`。
6. 解压后得到 `app-debug.apk`。
7. 建议重命名为 `WifiSpeakerMVP-v0.3.6-debug.apk`。

## v0.3.6 使用重点

- 发送端支持同时选择多个接收端。
- 搜索列表中的设备可以点击选择或取消选择。
- 手动输入框支持多个 IP，用逗号、空格或换行分隔。
- 音量滑条会控制所有已选择接收端的应用内播放音量。

## Windows 端云端构建

v0.3.6 起仓库还包含 Windows 端命令行程序。

1. 打开 GitHub 仓库网页。
2. 进入 `Actions -> Build Windows App`。
3. 等待最新构建变成绿色对勾。
4. 点进构建记录。
5. 在页面底部 `Artifacts` 下载 `WifiSpeakerMVP-v0.3.6-windows-x64`。
6. 解压后得到 `WifiSpeaker.Win.exe` 以及运行所需文件。

Windows 端用法：

```powershell
.\WifiSpeaker.Win.exe receiver
.\WifiSpeaker.Win.exe discover
.\WifiSpeaker.Win.exe send 192.168.1.35,192.168.1.36
.\WifiSpeaker.Win.exe volume 60 192.168.1.35,192.168.1.36
```
