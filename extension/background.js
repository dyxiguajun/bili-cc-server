import {DEFAULT_SERVER} from "./default-server.js";
import {serverUrl, shareableVideo} from "./links.js";
import {formatTranscript} from "./transcript.js";

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

const accountPaths = {
  session: "/api/session",
  start: "/api/login/start",
  poll: "/api/login/poll",
  logout: "/api/logout",
};

async function requestNas(path, body) {
  const {server = DEFAULT_SERVER} = await chrome.storage.local.get("server");
  const endpoint = serverUrl(server);
  if (!endpoint || !await chrome.permissions.contains({origins: [`https://${endpoint.hostname}/*`]})) {
    throw new Error("请在设置中保存并授权服务器地址。");
  }
  let response;
  try {
    response = await fetch(new URL(path, endpoint), {
      method: body === undefined ? "GET" : "POST",
      headers: body === undefined ? {} : {"X-Bili-Client": "web", "Content-Type": "application/json"},
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "include",
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(26000),
    });
  } catch {
    throw new Error("连不上服务器，请检查网络和设置。");
  }
  return {ok: response.ok, data: await response.json().catch(() => ({})), code: response.status};
}

async function account(action) {
  const path = Object.hasOwn(accountPaths, action) ? accountPaths[action] : null;
  if (!path) return {error: "不支持的操作。"};
  if (action === "logout") active = undefined;
  const {ok, data, code} = await requestNas(path, action === "session" ? undefined : {});
  if (!ok) {
    return {error: code === 403 ? "NAS 尚未开放扩展登录，请检查服务端版本。" : data.message || "暂时无法获取登录状态。"};
  }
  return {
    signedIn: data.signed_in === true,
    status: data.status,
    image: typeof data.image === "string" && /^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(data.image) ? data.image : undefined,
    expiresIn: data.expires_in,
  };
}

async function extract(request) {
  const {video, language, track} = request;
  const {ok, data} = await requestNas("/api/extract", {url: video, language, track});
  if (data.status === "login_required") {
    return {message: "此视频需要先登录 B 站。", loginRequired: true};
  }
  const tracks = data.tracks || [];
  if (!ok || data.status !== "ok" || !data.text) {
    return {message: messages[data.status] || data.message || "暂时取不到字幕。", tracks};
  }
  const {autoCopy = true, timestamps = false} = await chrome.storage.local.get(["autoCopy", "timestamps"]);
  if (active !== request) return {message: "本次提取已结束。"};

  const selected = tracks.find(item => item.id === data.selected);
  const result = {
    text: data.text,
    srt: data.srt,
    message: "已提取字幕",
    language: selected?.label || "",
    tracks,
  };
  const formatted = formatTranscript(data.text, data.srt, timestamps);
  if (formatted === null) return {...result, message: "当前字幕缺少时间戳。"};
  if (!autoCopy) return result;
  try {
    await copy(formatted);
    return {...result, copied: true, message: "字幕已复制"};
  } catch {
    return {...result, message: "自动复制失败，请点击「复制字幕」。"};
  }
}

function setBadge(text, color) {
  chrome.action.setBadgeBackgroundColor({color});
  chrome.action.setBadgeText({text});
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (sender.url !== chrome.runtime.getURL("popup.html")) return;
  if (message?.target === "account") {
    account(message.action).then(respond, error => respond({error: error.message || "操作失败。"}));
    return true;
  }
  if (message?.target === "copy-transcript") {
    if (typeof message.text !== "string" || !message.text) {
      respond({error: "没有可以复制的字幕。"});
      return;
    }
    copy(message.text).then(() => {
      setBadge("✓", "#087657");
      respond({copied: true});
    }, error => respond({error: error.message || "复制失败，请重试。"}));
    return true;
  }
  if (message?.target !== "extract") return;
  const video = shareableVideo(message.video);
  if (!video) {
    respond({message: "打开一个 B 站视频后再点击。"});
    return;
  }
  const language = ["zh", "en", "ja"].includes(message.language) ? message.language : undefined;
  const track = typeof message.track === "string" && /^[0-9]{1,25}$/.test(message.track) ? message.track : undefined;
  if (message.force === true || active?.video !== video || active.language !== language || active.track !== track) {
    const request = {video, language: track ? undefined : language, track};
    active = request;
    chrome.action.setBadgeText({text: ""});
    request.result = extract(request)
      .catch(error => ({message: error.message || "提取失败，请重试。"}))
      .then(result => {
        if (active === request) {
          if (result.copied) setBadge("✓", "#087657");
          else if (!result.text) setBadge("!", "#a33c4f");
          active = undefined;
        }
        return result;
      });
  }
  active.result.then(respond);
  return true;
});
