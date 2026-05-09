# 不安装 Android Studio 打包 APK

## 方法：GitHub Actions 在线打包

1. 在 GitHub 创建仓库。
2. 把本目录内容上传到仓库根目录，确保根目录下能直接看到：

```text
app/
.github/
build.gradle
settings.gradle
README.md
```

不要多套一层 `WifiSpeakerMVP/` 文件夹。

3. 进入仓库的 `Actions`。
4. 点击 `Build Android APK`。
5. 点击 `Run workflow`。
6. 等待构建完成。
7. 在构建详情页面底部下载 `Artifacts`。
8. 解压后得到 `app-debug.apk`。
9. 把 APK 安装到两台 Android 设备。

## 更新已有仓库

如果你已经用 GitHub Desktop 管理仓库：

1. 先确认当前可用版本已经 `Commit to main`。
2. 可选：给当前版本打 tag，例如 `v0.3.0`。
3. 打开本地仓库目录。
4. 删除旧的源码文件和目录，但不要删除 `.git`。
5. 把新版源码复制到仓库根目录。
6. 在 GitHub Desktop 中提交：`Release v0.3.4`。
7. `Push origin`。
8. 等 GitHub Actions 自动重新打包。

## v0.3.4 使用重点

- 主界面先选角色。
- 其中一台 Android 设备选择接收端并启动。
- 另一台 Android 设备选择发送端，搜索设备会出现列表。
- 点击列表中的设备后，再启动推送。
- 运行中按钮会自动切换成停止按钮。
- App 保留保守系统栏处理，避免界面内容被状态栏遮挡。
- 发送端界面可以调节接收端应用内播放音量，不改变发送端系统音量。
