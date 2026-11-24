# Bili CC Chrome 扩展

打开桌面 Chrome 里的 B 站视频，点工具栏上的 Bili CC 图标。扩展会检查当前页，找到中文字幕后直接复制。成功时会显示「字幕已复制」。

## 安装与首次配置

1. 使用 Chrome 116 或更新版本，打开 `chrome://extensions`。
2. 打开「开发者模式」，点击「加载未打包的扩展程序」，选择解压后的扩展目录。直接从源码安装时选 `extension/`。
3. 在 Chrome 的扩展菜单里固定 Bili CC。点图标，再点右上角齿轮。
4. 在展开的设置里填写服务的 HTTPS 根地址，例如 `https://your-nas.example.ts.net:8443/`。保存时允许扩展连接这个地址。

## 换语言和排查问题

- 左侧是这次提取的目标语言。菜单会列出检测到的字幕轨，选择另一轨会重新提取并复制。
- 齿轮里的「默认字幕语言」会在下次打开扩展时生效。默认是中文，可选英文、日文或跟随 B 站。
- **当前页不是 B 站视频**。在具体视频页重新点图标。
- **当前分 P 没有字幕**。检查该视频分 P 是否有对应字幕轨，或在左侧选另一轨。
- **需要 NAS 的 B 站登录态**。该视频的字幕受 B 站登录限制。扩展使用同一 Chrome 中 NAS 网站的现有会话。
- **连不上服务器**。检查当前网络能否打开所填的 HTTPS 地址，例如确认 Tailscale 已连接。

## 打包预填服务器的版本

在仓库根目录运行以下命令。输出为 `dist/bili-cc-chrome/` 和 `dist/bili-cc-chrome.zip`。

```sh
python3 scripts/package_chrome.py --server https://your-nas.example.ts.net:8443/
```

扩展在点击图标后读取当前标签页，仅把规范化后的视频地址、分 P 和语言或轨道选择发给配置好的服务。自动复制使用 Chrome 的离屏文档 API。[Chrome 官方剪贴板示例](https://github.com/GoogleChrome/chrome-extensions-samples/tree/main/functional-samples/cookbook.offscreen-clipboard-write)
