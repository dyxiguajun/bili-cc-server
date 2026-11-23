# Bili CC Server

在 NAS 上运行的 B 站字幕提取器。手机和电脑使用同一个网页，输入视频链接、b23.tv 短链接或 BV 号即可提取当前分 P 的字幕。选择字幕轨后可以复制文本或下载 SRT。

XiGua · Corvallis, Oregon, USA · 20261119

## 使用

1. 连接部署方的 Tailscale 网络，打开字幕服务的 HTTPS 地址。
2. 如果视频要求登录，点「登录 B 站」，用哔哩哔哩 App 扫码并确认。手机上可先试「在手机打开 B 站登录页」，也可把二维码显示在另一块屏幕上再扫码。
3. 粘贴链接或 BV 号，点「提取并复制」。如果浏览器需要一次额外的点击，点字幕区的「复制字幕」。
4. Android Chrome 和 iOS Safari 都可通过浏览器菜单把网页加到主屏幕。

Chrome 扩展会读取当前视频页的链接并打开填好地址的字幕网页。桌面 Chrome 打开 `chrome://extensions`，启用开发者模式，选择「加载已解压的扩展程序」，选中 `extension/`。第一次点击扩展图标时，在设置里填写字幕服务的 HTTPS 根地址。扩展使用 `activeTab`，无需读取 B 站 Cookie。

Android 客户端支持从 B 站分享到后台提取并复制，可以配置局域网与 Tailscale 的主用和备用连接。[Android 安装与构建说明](android/README.md)

需要分发已填地址的扩展时运行以下命令。

```sh
python3 scripts/package_chrome.py --server https://your-nas.example.ts.net:8443/
```

输出在 `dist/bili-cc-chrome/` 和 `dist/bili-cc-chrome.zip`。

## Docker 部署

NAS 需要 Docker Engine、Docker Compose 和到 B 站的网络连接。HTTPS 示例使用已启用 MagicDNS 和证书的 Tailscale Serve。创建一份本机的 `.env`，把下面的主机名改为 NAS 的 Tailscale 域名，端口按需调整。

```dotenv
BILI_PUBLIC_HOST=your-nas.example.ts.net
BILI_BIND_PORT=18743
```

在仓库目录启动服务并配置私有反代。

```sh
docker compose up --build -d --wait
curl --fail http://127.0.0.1:18743/healthz
sudo tailscale serve --bg --https=8443 http://127.0.0.1:18743
```

Compose 只绑定 NAS 的回环地址。Tailscale Serve 提供 HTTPS 并使用 tailnet 的访问规则。启用 HTTPS 可能需要 tailnet 管理员确认。[Tailscale Serve 文档](https://tailscale.com/docs/features/tailscale-serve)

## 运行和维护

- 容器以非 root 用户运行，根文件系统只读。Docker 会在进程异常退出时尝试重启，健康检查探测应用和 SQLite。
- 登录由 B 站二维码授权。NAS 的 `session_data` 卷保存当前浏览器对应的 B 站登录凭据，浏览器持有独立的会话令牌。会话最多 30 天，也可能在 B 站登录态到期时提前失效。网页里的「退出登录」撤销该浏览器的服务会话。
- 处理字幕请求有并发和超时限制。根据 BV、分 P 和字幕轨选择原始字幕，文本与 SRT 使用同一份字幕数据。字幕正文和访问链接不写入服务日志。
- 更新前可保留当前镜像标签用于回退。更新源码后运行 `docker compose up --build -d --wait`，再检查 `/healthz`。常规更新会继续使用原会话卷。
- 服务维护用 `docker compose ps` 和 `docker compose logs --tail=50 app`。如需停止服务，使用 `docker compose stop`。若只需移除 HTTPS 入口，运行 `sudo tailscale serve --https=8443 off`。
- `/healthz` 用于本地健康检查。字幕链路验收需要一条有字幕的视频，例如 `BV1BbKw6XEWq`，并按网页提示登录。B 站限制请求时，服务会提示稍后重试。

## 开发

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install --require-hashes -r requirements.txt
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m uvicorn bili_cc.app:app --host 127.0.0.1 --port 18743
```

通过 `http://localhost:18743/` 进行本机开发。远端部署使用 HTTPS，以支持安全会话与剪贴板。原桌面版仍可从旧仓库获取。 [Bili CC Extractor](https://github.com/dyxiguajun/bili-cc-extractor)

MIT License
