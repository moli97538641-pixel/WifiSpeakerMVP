# 不安装 Android Studio 的构建方式

## 方式 A：用 GitHub Actions 在线打包 APK，最省事

1. 登录 GitHub，新建一个空仓库。
2. 把本项目 `WifiSpeakerMVP` 目录里的所有文件上传到仓库根目录。
   - 注意：`app/`、`build.gradle`、`settings.gradle`、`.github/workflows/build-apk.yml` 都要在仓库根目录下。
3. 打开仓库页面的 **Actions** 标签页。
4. 选择 **Build Android APK**。
5. 点击 **Run workflow**。
6. 构建完成后，进入这次 workflow 的详情页。
7. 在页面底部 **Artifacts** 下载 `WifiSpeakerMVP-debug-apk`。
8. 解压后得到 `app-debug.apk`。
9. 把 APK 发到两台 Android 设备安装。

安装时如果系统提示“未知来源应用”，需要在设备设置中允许从当前来源安装。

## 方式 B：只装命令行工具，不装 Android Studio

需要：

- JDK 17
- Android SDK Command-line Tools
- Gradle 8.10.x

在项目根目录执行：

```bash
gradle :app:assembleDebug --no-daemon
```

构建结果在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 运行方法

1. 两台设备连接同一个 Wi-Fi。
2. 平板安装并启动 App，点击“平板：启动接收端”。
3. 记下平板显示的 IP 地址。
4. 另一台 Android 设备安装并启动 App，输入平板 IP。
5. 点击“手机：启动发送端”，同意系统录屏/音频采集授权。
6. 在发送端播放音乐或视频，声音会从平板播放。

## 注意

- 发送端必须是 Android 10/API 29 以上。
- 被推流的 App 必须允许系统音频采集。DRM、通话、部分播放器可能采不到。
- 当前是 debug APK，适合测试。正式发布需要签名 release APK。
