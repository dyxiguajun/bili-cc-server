<p align="center">
  <img src="web/icon-192.png" alt="Bili CC 图标" width="120">
</p>

<h1 align="center">Bili CC</h1>

<p align="center">在 B 站视频页点一下，把字幕复制出来。</p>

<p align="center">
  <a href="docs/server.md">部署服务</a> ·
  <a href="extension/README.md">Chrome</a> ·
  <a href="android/README.md">Android</a>
</p>

把服务部署在自己的 NAS 或 Linux 主机上。Chrome 扩展、Android 客户端和网页都能用同一台服务提取字幕。

## 选一个入口

| 入口 | 怎么提取 |
| --- | --- |
| **Chrome** | 打开 B 站视频，点工具栏里的 Bili CC 图标。提取成功后默认复制到剪贴板，可在浮窗里预览和换字幕轨。 |
| **Android** | 在 B 站点「分享 → 更多 → Bili CC 字幕」。可以复制文本，也可以保存为 SRT。 |
| **网页** | 打开服务地址，粘贴视频链接或 BV 号。适合临时提取或下载 SRT。 |

部分字幕需要登录后才能看到。网页和 Chrome 浮窗可用哔哩哔哩 App 扫码，登录后再选择要提取的字幕轨。

## 部署到 NAS

按[部署说明](docs/server.md)在 NAS 或 Linux 上启动 Docker Compose，配置 HTTPS 入口。用浏览器打开服务地址，确认网页能提取字幕，再把同一个 HTTPS 根地址填到 Chrome 或 Android。局域网和 Tailscale 可以同时使用，见[局域网配置](ops/lan/README.md)。

只在一台电脑上提取时，也可以用[原桌面版](https://github.com/dyxiguajun/bili-cc-extractor)。本仓库代码使用 [MIT 许可](LICENSE)。
