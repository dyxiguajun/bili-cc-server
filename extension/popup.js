import {DEFAULT_SERVER} from "./default-server.js";
import {serverUrl, shareableVideo} from "./links.js";
import {mountAccount} from "./account.js";

const get = id => document.getElementById(id);
const status = get("status");
const target = get("target");
const tracks = get("tracks");
const languageLabel = get("language");
const settings = get("settings");
const form = get("preferences");
const saved = get("saved");
let video;
let revision = 0;

function sizePopup() {
  document.body.classList.toggle("expanded", !form.hidden || !get("account-panel").hidden);
}

const account = mountAccount({
  onPanelChange(open) {
    if (open) {
      form.hidden = true;
      settings.setAttribute("aria-expanded", "false");
    }
    sizePopup();
  },
  onSignedIn: () => run(true),
});

settings.addEventListener("click", () => {
  const open = form.hidden;
  if (open) account.close();
  form.hidden = !open;
  settings.setAttribute("aria-expanded", String(open));
  sizePopup();
});

form.addEventListener("submit", async event => {
  event.preventDefault();
  const endpoint = serverUrl(get("server").value);
  if (!endpoint) {
    saved.textContent = "请输入服务的 HTTPS 根地址。";
    return;
  }
  try {
    const granted = await chrome.permissions.request({origins: [`https://${endpoint.hostname}/*`]});
    if (!granted) {
      saved.textContent = "需要允许扩展连接此服务器。";
      return;
    }
    await chrome.storage.local.set({server: endpoint.href, language: get("default-language").value});
    get("server").value = endpoint.href;
    saved.textContent = "已保存。";
    void account.refresh();
  } catch {
    saved.textContent = "无法保存设置，请重试。";
  }
});

function showTracks(detected) {
  if (!Array.isArray(detected)) return;
  const current = target.value;
  const options = detected
    .filter(track => typeof track.id === "string" && /^[0-9]{1,25}$/.test(track.id))
    .map(track => new Option(track.label || track.language, `track:${track.id}`));
  tracks.replaceChildren(...options);
  tracks.hidden = !options.length;
  if ([...target.options].some(option => option.value === current)) target.value = current;
}

async function run(force = false) {
  const attempt = ++revision;
  delete status.dataset.copied;
  languageLabel.hidden = true;
  if (!video) {
    target.disabled = true;
    status.textContent = "当前页不是 B 站视频。";
    return;
  }
  status.textContent = "正在提取字幕…";
  const choice = target.value;
  const selection = choice.startsWith("track:") ? {track: choice.slice(6)} : {language: choice};
  try {
    const result = await chrome.runtime.sendMessage({target: "extract", video, force, ...selection});
    if (attempt !== revision) return;
    status.textContent = result.message;
    if (result.loginRequired) account.requireLogin();
    showTracks(result.tracks);
    if (result.copied) status.dataset.copied = "";
    if (result.language) {
      languageLabel.textContent = result.language;
      languageLabel.hidden = false;
    }
  } catch {
    if (attempt === revision) status.textContent = "提取失败，请重试。";
  }
}

target.addEventListener("change", () => run());

try {
  const {server = DEFAULT_SERVER, language = "zh"} = await chrome.storage.local.get(["server", "language"]);
  get("server").value = server;
  get("default-language").value = target.value = language;
  void account.refresh();
  const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
  video = shareableVideo(tab?.url);
  await run();
} catch {
  status.textContent = "无法读取当前标签页。";
}
