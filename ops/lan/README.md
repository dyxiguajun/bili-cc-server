# NAS 局域网 HTTPS 入口

Linux NAS 有 systemd 且已配置专用的 Bili CC Tailscale Serve 端口时，可用 `systemd-socket-proxyd` 将局域网接口上的同一端口连接到本机的 Tailscale Serve。TLS 由现有 Serve 服务处理，域名、证书续期和应用会话都可复用。

先在 NAS 上验证它可以连接自己的 Tailscale Serve。替换下面的域名、地址和端口。

```sh
curl --fail --resolve your-nas.example.ts.net:8443:100.64.0.10 \
  https://your-nas.example.ts.net:8443/healthz
```

将两个 `.in` 模板复制为文件名去掉 `.in` 的 systemd 单元，分别填写 `@LAN_IP@`、`@LAN_INTERFACE@`、`@TAILSCALE_IP@`、`@HTTPS_PORT@`。局域网监听绑定所填的私有 IP 和网卡。将单元文件安装到 `/etc/systemd/system/` 后启用 socket。

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now bili-cc-lan.socket
sudo systemctl status bili-cc-lan.socket
```

从同一局域网另一台设备测试，`--resolve` 使用 NAS 局域网 IP。Android 连接设置里填写证书 HTTPS 地址，把实际连接 IP 填成局域网地址即可。若 NAS 的局域网 IP 变更，同时更新监听单元和 App 连接设置。

```sh
curl --fail --resolve your-nas.example.ts.net:8443:192.168.1.20 \
  https://your-nas.example.ts.net:8443/healthz
```
