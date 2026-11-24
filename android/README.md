# Bili CC Android

Android 客户端使用现有 Bili CC Server 的 HTTPS 接口。支持手动提取、字幕轨切换、复制和保存 SRT，也可以接收 B 站分享的文本。

## 使用

1. 安装签名 APK。在「连接设置」添加或检查服务地址，点「测试」检查每条连接，保存后可随时调整顺序。
2. 如果目标视频需要登录，在 App 内打开「B 站登录」，用哔哩哔哩 App 扫码并确认。使用一台手机时，可以保存二维码，再从 B 站扫码页的相册选项读取。也可以用另一台设备显示二维码。
3. 在哔哩哔哩视频页点「分享 → 更多 → Bili CC 字幕」。有可用的中文字幕时，App 会优先选择简体并复制，随后显示「中文字幕已复制」。

分享使用 Android 原生 Toast 显示提取进度和结果。Android 12 及以上可在「连接设置」手动打开「分享后后台提取」。ColorOS 可能屏蔽后台提示。[Android 分享入口](https://developer.android.com/training/sharing/receive) · [Android 加急任务](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean))

## 连接设置

按列表顺序尝试 HTTPS 连接。首选连接不可用时才会换到下一条。每条连接可填独立 HTTPS 地址，也可以保留证书域名并指定一个实际连接 IP。局域网内可直接使用 NAS 地址，外出可使用已连接的 Tailscale。

一台 NAS 的局域网和 Tailscale 使用同一证书域名时，App 可在两条网络之间复用该 NAS 的会话。会话令牌由 Android Keystore 加密保存，TLS 仍会按用户配置的服务域名验证证书。

## 构建

需要 JDK 17 或 21、Android SDK 36 和 SDK Build Tools。用标准 Gradle wrapper 构建和检查。

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

通用构建首次启动时提示填写地址。为私人部署预填连接时，在本地创建已忽略的 `local.routes.json`。

```json
[
  {"name":"局域网","url":"https://your-nas.example.ts.net:8443/","ip":"192.168.1.20"},
  {"name":"Tailscale","url":"https://your-nas.example.ts.net:8443/","ip":""}
]
```

```sh
./gradlew :app:assembleRelease -PbiliRoutesFile=local.routes.json
```

对 `app/build/outputs/apk/release/app-release-unsigned.apk` 执行 `zipalign`，再用自己的 Android 发布密钥签名。后续安装更新应继续使用同一签名密钥。

同一 NAS 的两条网络入口可用 systemd Socket 实现。[局域网入口部署](../ops/lan/README.md)
