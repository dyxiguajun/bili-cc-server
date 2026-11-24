# Bili CC

在 B 站视频页点一下 Chrome 工具栏的 Bili CC 图标，字幕就会复制到剪贴板。也可以在 Android 的分享菜单里提取，或在网页上粘贴链接并下载 SRT。

## 选择你的用法

| 设备 | 怎么用 |
| --- | --- |
| 桌面 Chrome | 点扩展图标提取当前视频。默认中文，左侧可换字幕轨，齿轮里可改服务器和默认语言。[Chrome 使用说明](extension/README.md) |
| Android | 在 B 站点「分享 → 更多 → Bili CC 字幕」。[Android 使用说明](android/README.md) |
| 网页 | 打开你的 NAS 服务，粘贴链接或 BV 号，点「提取并复制」。 |

## 自己部署

1. 在 NAS 或 Linux 主机准备 Docker Compose。
2. 按部署说明填写 HTTPS 域名，启动容器并配置访问入口。
3. 把服务的 HTTPS 根地址填入 Chrome 扩展或 Android 客户端。[NAS 部署与维护](docs/server.md)

同一台 NAS 可以同时提供局域网和 Tailscale 入口。[局域网 HTTPS 配置](ops/lan/README.md)

Chrome 和 Android 会显示当前视频有没有选定的字幕轨。B 站的部分字幕需要先在对应的客户端建立登录会话。网页端可使用哔哩哔哩 App 扫描登录二维码。

## 项目目录

| 目录 | 内容 |
| --- | --- |
| `extension/` | Chrome 扩展 |
| `android/` | Android 客户端 |
| `web/` | 网页客户端 |
| `bili_cc/` | NAS 接口、字幕提取和会话管理 |
| `docs/`、`ops/` | 部署说明与局域网配置 |

XiGua · Corvallis, Oregon, USA

[MIT License](LICENSE) · [原桌面版](https://github.com/dyxiguajun/bili-cc-extractor)
