# NAS 部署与维护

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
