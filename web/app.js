const byId = id => document.getElementById(id);
const form = byId("extract-form");
const urlInput = byId("video-url");
const extractButton = byId("extract-button");
const trackSelect = byId("track");
const details = byId("video-details");
const title = byId("video-title");
const part = byId("video-part");
const subtitle = byId("subtitle");
const copyButton = byId("copy-button");
const srtButton = byId("srt-button");
const status = byId("status");
const loginButton = byId("login-button");
const logoutButton = byId("logout-button");
const loginDialog = byId("login-dialog");
const qrImage = byId("qr-image");
const qrActions = byId("qr-actions");
const qrRefresh = byId("qr-refresh");
const loginStatus = byId("login-status");

let busy = false;
let result = null;
let requestNumber = 0;
let qrAttempt = 0;
let pollTimer;

async function api(path, options = {}) {
  const {method = "GET", body} = options;
  let response;
  try {
    response = await fetch(path, {
      method,
      mode: "same-origin",
      cache: "no-store",
      credentials: "same-origin",
      headers: {"X-Bili-Client": "web", ...(body && {"Content-Type": "application/json"})},
      body: body && JSON.stringify(body),
      signal: AbortSignal.timeout(27000),
    });
  } catch {
    throw new Error("连接服务失败，请检查 Tailscale 后重试。");
  }

  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.message || (response.status === 502 ? "字幕服务正在重启，请稍后再试。" : "服务暂时出错，请稍后再试。"));
    error.status = data.status;
    error.retryAfter = Number(response.headers.get("Retry-After")) || 0;
    throw error;
  }
  return data;
}

function setStatus(message, kind = "normal") {
  status.textContent = message;
  status.dataset.kind = kind;
}

function setAccount(signedIn) {
  byId("signed-in").hidden = !signedIn;
  loginButton.hidden = signedIn;
  logoutButton.hidden = !signedIn;
}

function clearResult() {
  result = null;
  details.hidden = true;
  trackSelect.hidden = true;
  subtitle.value = "";
  copyButton.disabled = true;
  srtButton.disabled = true;
}

function showVideo(data) {
  if (!data.video) return;
  title.textContent = data.video.title;
  title.href = data.video.url;
  part.textContent = data.video.part;
  details.hidden = false;

  trackSelect.replaceChildren(...data.tracks.map(track => new Option(track.label, track.id)));
  if (data.status === "track_unavailable") {
    trackSelect.prepend(new Option("选择其他字幕轨", "", true, true));
    trackSelect.options[0].disabled = true;
  } else if (data.selected) {
    trackSelect.value = data.selected;
  }
  trackSelect.hidden = data.tracks.length <= 1 && data.status !== "track_unavailable";
}

function copyWithGesture(response) {
  if (!navigator.clipboard?.write || !window.ClipboardItem) return null;
  const text = response.then(data => {
    if (data.status !== "ok" || !data.text) throw new Error("No subtitle to copy");
    return new Blob([data.text], {type: "text/plain"});
  });
  text.catch(() => {});
  try {
    return navigator.clipboard.write([new ClipboardItem({"text/plain": text})]).then(() => true, () => false);
  } catch {
    return null;
  }
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

async function extract(track, shouldCopy) {
  if (busy) return;
  const query = urlInput.value.trim();
  if (!query) return;
  const number = ++requestNumber;
  clearResult();
  busy = true;
  urlInput.readOnly = true;
  extractButton.disabled = true;
  setStatus("正在提取字幕…");

  const response = api("/api/extract", {method: "POST", body: {url: query, track}});
  const clipboard = shouldCopy ? copyWithGesture(response) : null;
  try {
    const data = await response;
    showVideo(data);
    if (data.status === "ok") {
      result = data;
      subtitle.value = data.text;
      copyButton.disabled = false;
      srtButton.disabled = false;
      setStatus("已提取字幕。");
      if (shouldCopy) {
        const copied = await (clipboard || copyText(data.text));
        if (number === requestNumber) {
          setStatus(copied ? "字幕已复制到剪贴板。" : "已提取字幕。点「复制字幕」将文本复制到剪贴板。");
        }
      }
    } else {
      const messages = {
        login_required: "这条视频需要先登录 B 站。扫码确认后，再点「提取并复制」。",
        no_subtitle: "这条视频的当前分 P 没有可提取的字幕。",
        track_unavailable: "所选字幕轨已不可用，请选择其他字幕轨。",
      };
      setStatus(messages[data.status] || "暂未获取到字幕。");
    }
  } catch (error) {
    if (error.status === "login_required") setAccount(false);
    setStatus(error.message, "error");
  } finally {
    busy = false;
    urlInput.readOnly = false;
    extractButton.disabled = false;
  }
}

form.addEventListener("submit", event => {
  event.preventDefault();
  extract(null, true);
});
trackSelect.addEventListener("change", () => extract(trackSelect.value, true));
urlInput.addEventListener("input", () => {
  requestNumber++;
  clearResult();
  setStatus("");
});

copyButton.addEventListener("click", async () => {
  if (!result) return;
  const number = requestNumber;
  const copied = await copyText(result.text);
  if (number !== requestNumber) return;
  if (copied) {
    setStatus("字幕已复制到剪贴板。");
  } else {
    subtitle.focus();
    subtitle.select();
    setStatus("浏览器未授权复制。文本已选中，可以使用系统复制。");
  }
});

srtButton.addEventListener("click", () => {
  if (!result) return;
  const file = new Blob([result.srt], {type: "application/x-subrip;charset=utf-8"});
  const href = URL.createObjectURL(file);
  const download = document.createElement("a");
  download.href = href;
  download.download = `${result.video.bvid}-P${result.video.page}-${result.selected}.srt`;
  download.click();
  setTimeout(() => URL.revokeObjectURL(href), 1000);
});

function stopPolling() {
  clearTimeout(pollTimer);
  qrAttempt++;
}

function showExpired(message) {
  loginStatus.textContent = message;
  qrImage.hidden = true;
  qrActions.hidden = true;
  qrRefresh.hidden = false;
}

async function startLogin() {
  stopPolling();
  const attempt = qrAttempt;
  const current = () => loginDialog.open && qrAttempt === attempt;
  qrImage.hidden = true;
  qrActions.hidden = true;
  qrRefresh.hidden = true;
  loginStatus.textContent = "正在请求登录二维码…";
  try {
    const challenge = await api("/api/login/start", {method: "POST"});
    if (!current()) return;
    qrImage.src = challenge.image;
    byId("qr-open").href = challenge.url;
    byId("qr-save").href = challenge.image;
    qrImage.hidden = false;
    qrActions.hidden = false;
    loginStatus.textContent = "等待扫描二维码。";
    const deadline = Date.now() + challenge.expires_in * 1000;

    async function poll() {
      if (!current()) return;
      if (Date.now() >= deadline) {
        showExpired("二维码已过期，请刷新。");
        return;
      }
      let delay = 2400;
      try {
        const data = await api("/api/login/poll", {method: "POST"});
        if (!current()) return;
        if (data.status === "signed_in") {
          setAccount(true);
          loginDialog.close();
          setStatus("B 站已登录。可以提取字幕了。");
          return;
        }
        if (data.status === "expired") {
          showExpired("二维码已过期，请刷新。");
          return;
        }
        if (data.status === "waiting_confirm") {
          loginStatus.textContent = "已扫码，请在哔哩哔哩 App 内确认。";
        }
      } catch (error) {
        if (!current()) return;
        if (error.status === "login_response") {
          showExpired(error.message);
          return;
        }
        delay = Math.max(4500, (error.retryAfter || 0) * 1000);
        loginStatus.textContent = "登录状态暂时无法获取，正在重试。";
      }
      pollTimer = setTimeout(poll, delay);
    }

    pollTimer = setTimeout(poll, 2400);
  } catch (error) {
    if (current()) showExpired(error.message);
  }
}

loginButton.addEventListener("click", () => {
  loginDialog.showModal();
  startLogin();
});
qrRefresh.addEventListener("click", startLogin);
loginDialog.addEventListener("close", stopPolling);
logoutButton.addEventListener("click", async () => {
  logoutButton.disabled = true;
  try {
    await api("/api/logout", {method: "POST"});
    setAccount(false);
    clearResult();
    setStatus("当前浏览器已退出 B 站登录。");
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    logoutButton.disabled = false;
  }
});

const incoming = new URLSearchParams(location.hash.slice(1)).get("video");
if (incoming) {
  urlInput.value = incoming.slice(0, 2048);
  history.replaceState(null, "", location.pathname + location.search);
}
api("/api/session").then(data => setAccount(data.signed_in)).catch(error => {
  setAccount(false);
  setStatus(error.message, "error");
});
