# Bili CC Chrome 扩展

打开桌面 Chrome 里的 B 站视频，点工具栏上的 Bili CC 图标。扩展会检查当前页，找到中文字幕后直接复制。成功时会显示「字幕已复制」。

## 安装与首次配置

1. 使用 Chrome 116 或更新版本，打开 `chrome://extensions`。
2. 打开「开发者模式」，点击「加载未打包的扩展程序」，选择解压后的扩展目录。直接从源码安装时选 `extension/`。
3. 在 Chrome 的扩展菜单里固定 Bili CC。点图标，再点右上角齿轮。
4. 在展开的设置里填写服务的 HTTPS 根地址，例如 `https://your-nas.example.ts.net:8443/`。保存时允许扩展连接这个地址。

## 在浮窗里登录

点「登录 B 站」，在浮窗中用哔哩哔哩 App 扫码并确认。登录后，打开的视频会自动重新提取一次。右上角会显示「已登录」，点开可退出 NAS 登录。扩展与同一 Chrome 配置中的 NAS 网页共用会话，退出后两处都需要重新登录。

B 站登录凭据由 NAS 处理。Chrome 通过 HTTPS 携带 NAS 设置的 `Secure`、`HttpOnly` 会话 Cookie。扩展无需访问 B 站站点 Cookie 的权限。

## 换语言和排查问题

- 左侧是这次提取的目标语言。菜单会列出检测到的字幕轨，选择另一轨会重新提取并复制。
- 齿轮里的「默认字幕语言」会在下次打开扩展时生效。默认是中文，可选英文、日文或跟随 B 站。
- **当前页不是 B 站视频**。在具体视频页重新点图标。
- **当前分 P 没有字幕**。检查该视频分 P 是否有对应字幕轨，或在左侧选另一轨。
- **此视频需要先登录 B 站**。点浮窗右上角的登录按钮。若服务器拒绝扩展登录，把 Bili CC Server 更新到 v2.0.3 或更新版本。
- **连不上服务器**。检查当前网络能否打开所填的 HTTPS 地址，例如确认 Tailscale 已连接。

## 打包预填服务器的版本

在仓库根目录运行以下命令。输出为 `dist/bili-cc-chrome/` 和 `dist/bili-cc-chrome.zip`。

```sh
python3 scripts/package_chrome.py --server https://your-nas.example.ts.net:8443/
```

扩展在点击图标后读取当前标签页，仅把规范化后的视频地址、分 P 和语言或轨道选择发给配置好的服务。自动复制使用 Chrome 的离屏文档 API。[Chrome 官方剪贴板示例](https://github.com/GoogleChrome/chrome-extensions-samples/tree/main/functional-samples/cookbook.offscreen-clipboard-write)
