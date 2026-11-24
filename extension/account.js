const avatarSource = /^https:\/\/i[0-9]+\.hdslb\.com\/bfs\/face\/(?:[\w-]+\/)*[\w-]+\.(?:jpe?g|png|webp|gif)$/;

export function mountAccount({onPanelChange, onSignedIn}) {
  const button = document.getElementById("account");
  const loginLabel = document.getElementById("login-label");
  const placeholder = document.getElementById("account-placeholder");
  const avatar = document.getElementById("account-avatar");
  const panel = document.getElementById("account-panel");
  const qr = document.getElementById("qr");
  const status = document.getElementById("account-status");
  const action = document.getElementById("account-action");
  let signedIn = false;
  let timer;
  let revision = 0;
  let avatarRevision = 0;

  async function call(actionName) {
    const reply = await chrome.runtime.sendMessage({target: "account", action: actionName});
    if (!reply || reply.error) throw new Error(reply?.error || "暂时无法连接 NAS。");
    return reply;
  }

  function setSignedIn(value) {
    signedIn = value;
    button.dataset.signedIn = String(value);
    loginLabel.hidden = value;
    placeholder.hidden = !value;
    avatar.hidden = true;
    avatar.removeAttribute("src");
    const label = value ? "B 站账号（已登录）" : "点击登录";
    button.setAttribute("aria-label", label);
    button.title = label;
    const current = ++avatarRevision;
    if (value) void loadAvatar(current);
  }

  async function loadAvatar(current) {
    try {
      const {avatar: url} = await call("avatar");
      if (current !== avatarRevision || typeof url !== "string" || !avatarSource.test(url)) return;
      avatar.src = url;
    } catch {
      // The optional avatar does not change the login state.
    }
  }

  avatar.addEventListener("load", () => {
    if (!signedIn || !avatar.hasAttribute("src")) return;
    avatar.hidden = false;
    placeholder.hidden = true;
  });

  function setPanel(open) {
    panel.hidden = !open;
    button.setAttribute("aria-expanded", String(open));
    onPanelChange(open);
  }

  function stopScan() {
    clearTimeout(timer);
    qr.hidden = true;
    qr.removeAttribute("src");
    return ++revision;
  }

  function close() {
    stopScan();
    setPanel(false);
  }

  function showSignedIn() {
    status.textContent = "与此 Chrome 中的 NAS 网页共用登录状态。";
    action.textContent = "退出 NAS 登录";
    action.hidden = false;
  }

  function showRetry(message) {
    stopScan();
    status.textContent = message;
    action.textContent = "刷新二维码";
    action.hidden = false;
  }

  async function start() {
    const current = stopScan();
    status.textContent = "正在获取二维码…";
    action.hidden = true;
    try {
      const reply = await call("start");
      if (current !== revision) return;
      if (!reply.image) throw new Error("NAS 没有返回有效的登录二维码。");
      qr.src = reply.image;
      qr.hidden = false;
      status.textContent = "用哔哩哔哩 App 扫码并确认登录。";
      const seconds = Number(reply.expiresIn);
      const expiry = Date.now() + 1000 * (seconds > 0 ? Math.min(seconds, 180) : 180);
      timer = setTimeout(() => poll(current, expiry), 2400);
    } catch (error) {
      if (current === revision) showRetry(error.message);
    }
  }

  async function poll(current, expiry) {
    if (current !== revision) return;
    if (Date.now() >= expiry) {
      showRetry("二维码已过期，请刷新。");
      return;
    }
    let nextPoll = 2400;
    try {
      const {status: state} = await call("poll");
      if (current !== revision) return;
      if (state === "signed_in") {
        const session = await call("session");
        if (current !== revision) return;
        if (!session.signedIn) {
          showRetry("Chrome 未保存 NAS 会话。请检查站点的 Cookie 设置。");
          return;
        }
        setSignedIn(true);
        close();
        onSignedIn();
        return;
      }
      if (state === "expired") {
        showRetry("二维码已失效，请刷新。");
        return;
      }
      if (state === "waiting_confirm") status.textContent = "已扫码，请在手机上确认。";
      if (state === "waiting_scan") status.textContent = "用哔哩哔哩 App 扫码并确认登录。";
    } catch {
      if (current !== revision) return;
      status.textContent = "登录状态暂时不可用，正在重试。";
      nextPoll = 4200;
    }
    timer = setTimeout(() => poll(current, expiry), nextPoll);
  }

  button.addEventListener("click", () => {
    if (!panel.hidden) {
      close();
      return;
    }
    setPanel(true);
    if (signedIn) showSignedIn();
    else start();
  });

  action.addEventListener("click", async () => {
    if (!signedIn) {
      await start();
      return;
    }
    const current = stopScan();
    action.disabled = true;
    try {
      await call("logout");
      if (current !== revision) return;
      setSignedIn(false);
      showRetry("已退出 NAS 登录。");
      action.textContent = "重新登录";
    } catch (error) {
      if (current === revision) status.textContent = error.message;
    } finally {
      action.disabled = false;
    }
  });

  return {
    close,
    requireLogin() { setSignedIn(false); },
    async refresh() {
      const current = revision;
      try {
        const reply = await call("session");
        if (current === revision) setSignedIn(reply.signedIn);
      } catch {
        if (current === revision) setSignedIn(false);
      }
    },
  };
}
