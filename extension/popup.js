import {DEFAULT_SERVER} from "./default-server.js";
import {serverUrl, shareableVideo} from "./links.js";
import {mountAccount} from "./account.js";
import {formatTranscript} from "./transcript.js";

const get = id => document.getElementById(id);
const status = get("status");
const target = get("target");
const tracks = get("tracks");
const languageLabel = get("language");
const settings = get("settings");
const form = get("preferences");
const saved = get("saved");
const transcript = get("transcript");
const preview = get("preview");
const copyButton = get("copy");
const autoCopyField = get("auto-copy");
const timestampsField = get("timestamps");
let video;
let result;
let timestamps = false;
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
    await chrome.storage.local.set({
      server: endpoint.href,
      language: get("default-language").value,
      autoCopy: autoCopyField.checked,
      timestamps: timestampsField.checked,
    });
    const formatChanged = timestamps !== timestampsField.checked;
    timestamps = timestampsField.checked;
    if (formatChanged && result) {
      renderPreview();
      showStatus(copyButton.disabled ? "当前字幕缺少时间戳。" : "预览已更新，点「复制字幕」应用。");
    }
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

function showStatus(message, copied = false) {
  delete status.dataset.copied;
  status.textContent = message;
  if (copied) status.dataset.copied = "";
}

function renderPreview() {
  transcript.hidden = !result;
  const formatted = result ? formatTranscript(result.text, result.srt, timestamps) : null;
  preview.textContent = formatted ?? result?.text ?? "";
  preview.scrollTop = 0;
  copyButton.disabled = !formatted;
}

copyButton.addEventListener("click", async () => {
  if (!result) return;
  const text = formatTranscript(result.text, result.srt, timestamps);
  if (!text) return;
  const attempt = revision;
  copyButton.disabled = true;
  try {
    const reply = await chrome.runtime.sendMessage({target: "copy-transcript", text});
    if (attempt !== revision) return;
    showStatus(reply?.copied ? "字幕已复制" : reply?.error || "复制失败，请重试。", reply?.copied);
  } catch {
    if (attempt === revision) showStatus("复制失败，请重试。");
  } finally {
    if (attempt === revision) copyButton.disabled = !formatTranscript(result.text, result.srt, timestamps);
  }
});

async function run(force = false) {
  const attempt = ++revision;
  result = undefined;
  renderPreview();
  languageLabel.hidden = true;
  if (!video) {
    target.disabled = true;
    showStatus("当前页不是 B 站视频。");
    return;
  }
  showStatus("正在提取字幕…");
  const choice = target.value;
  const selection = choice.startsWith("track:") ? {track: choice.slice(6)} : {language: choice};
  try {
    const reply = await chrome.runtime.sendMessage({target: "extract", video, force, ...selection});
    if (attempt !== revision) return;
    result = reply.text ? reply : undefined;
    renderPreview();
    showStatus(reply.message, reply.copied);
    if (reply.loginRequired) account.requireLogin();
    showTracks(reply.tracks);
    if (reply.language) {
      languageLabel.textContent = reply.language;
      languageLabel.hidden = false;
    }
  } catch {
    if (attempt === revision) showStatus("提取失败，请重试。");
  }
}

target.addEventListener("change", () => run());

try {
  const {server = DEFAULT_SERVER, language = "zh", autoCopy = true, timestamps: configuredTimestamps = false} =
    await chrome.storage.local.get(["server", "language", "autoCopy", "timestamps"]);
  autoCopyField.checked = autoCopy;
  timestampsField.checked = timestamps = configuredTimestamps;
  get("server").value = server;
  get("default-language").value = target.value = language;
  void account.refresh();
  const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
  video = shareableVideo(tab?.url);
  await run();
} catch {
  status.textContent = "无法读取当前标签页。";
}
