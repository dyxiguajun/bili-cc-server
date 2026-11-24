import {DEFAULT_SERVER} from "./default-server.js";
import {serverUrl, shareableVideo} from "./links.js";

let creating;
let active;
const clipboardPage = "clipboard.html";

async function copy(text) {
  const documentUrl = chrome.runtime.getURL(clipboardPage);
  const contexts = await chrome.runtime.getContexts({contextTypes: ["OFFSCREEN_DOCUMENT"], documentUrls: [documentUrl]});
  if (!contexts.length) {
    creating ??= chrome.offscreen.createDocument({
      url: clipboardPage,
      reasons: ["CLIPBOARD"],
      justification: "Copy the requested video subtitles when the toolbar popup closes.",
    }).finally(() => { creating = undefined; });
    await creating;
  }
  const reply = await chrome.runtime.sendMessage({target: "clipboard", text});
  if (!reply?.copied) throw new Error("Chrome 未能写入剪贴板，请重试。");
}

const messages = {
  no_subtitle: "当前分 P 没有可提取的字幕。",
  no_chinese_subtitle: "当前分 P 没有中文字幕。",
  no_language_subtitle: "当前分 P 没有所选语言的字幕。",
};

async function extract(request) {
  const {video, language, track} = request;
  const {server = DEFAULT_SERVER} = await chrome.storage.local.get("server");
  const endpoint = serverUrl(server);
  if (!endpoint || !await chrome.permissions.contains({origins: [`https://${endpoint.hostname}/*`]})) {
    return {message: "请在设置中保存并授权服务器地址。"};
  }
  let response;
  try {
    response = await fetch(new URL("/api/extract", endpoint), {
      method: "POST",
      headers: {"X-Bili-Client": "web", "Content-Type": "application/json"},
      body: JSON.stringify({url: video, language, track}),
      credentials: "include",
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(26000),
    });
  } catch {
    return {message: "连不上服务器，请检查网络和设置。"};
  }
  const data = await response.json().catch(() => ({}));
  if (data.status === "login_required") {
    return {message: "此视频需要 NAS 的 B 站登录态。"};
  }
  const tracks = data.tracks || [];
  if (!response.ok || data.status !== "ok" || !data.text) {
    return {message: messages[data.status] || data.message || "暂时取不到字幕。", tracks};
  }
  if (active !== request) return {message: "已改为提取另一条视频。"};
  await copy(data.text);
  const selected = tracks.find(item => item.id === data.selected);
  return {copied: true, message: "字幕已复制", language: selected?.label || "", tracks};
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message?.target !== "extract" || sender.url !== chrome.runtime.getURL("popup.html")) return;
  const video = shareableVideo(message.video);
  if (!video) {
    respond({message: "打开一个 B 站视频后再点击。"});
    return;
  }
  const language = ["zh", "en", "ja"].includes(message.language) ? message.language : undefined;
  const track = typeof message.track === "string" && /^[0-9]{1,25}$/.test(message.track) ? message.track : undefined;
  if (active?.video !== video || active.language !== language || active.track !== track) {
    const request = {video, language: track ? undefined : language, track};
    active = request;
    chrome.action.setBadgeText({text: ""});
    request.result = extract(request)
      .catch(error => ({message: error.message || "提取失败，请重试。"}))
      .then(result => {
        if (active === request) {
          chrome.action.setBadgeBackgroundColor({color: result.copied ? "#087657" : "#a33c4f"});
          chrome.action.setBadgeText({text: result.copied ? "✓" : "!"});
          active = undefined;
        }
        return result;
      });
  }
  active.result.then(respond);
  return true;
});
