# 不安装 Android Studio 的 APK 构建方法

## 方式：GitHub Actions 云端构建

1. 新建 GitHub 仓库，建议不要自动添加 README、.gitignore 或 license。
2. 把本项目内容上传到仓库根目录。
3. 根目录必须直接看到：

```text
app/
.github/
build.gradle
settings.gradle
README.md
```

不要多套一层 `WifiSpeakerMVP/` 文件夹。

4. 进入仓库顶部的 Actions。
5. 左侧选择 `Build Android APK`。
6. 点击 `Run workflow`。
7. 等待绿色对勾。
8. 打开构建详情，在底部 `Artifacts` 下载 `WifiSpeakerMVP-debug-apk`。
9. 解压得到 `app-debug.apk`。
10. 安装到平板和发送端 Android 设备。

## 更新已有仓库

如果你已经上传过旧版本：

1. 用 GitHub Desktop 打开本地仓库。
2. 删除旧的 `app`、`.github`、`build.gradle`、`settings.gradle`、`README.md`、`BUILD_WITHOUT_ANDROID_STUDIO.md`。
3. 不要删除 `.git` 文件夹。
4. 复制新版项目内容到仓库根目录。
5. 在 GitHub Desktop 中提交，例如 `Update to v0.2.0`。
6. 点击 `Push origin`。
7. GitHub Actions 会自动重新打包。

## v0.2.0 使用说明

平板端先点：

```text
平板：启动接收端 / Speaker
```

手机端可以点：

```text
搜索平板 / Auto discover
```

搜到后再点：

```text
手机：启动发送端 / Push audio
```

如果搜索不到，手动输入平板上显示的 IP 也可以继续使用。
