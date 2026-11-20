import {DEFAULT_SERVER} from "./default-server.js";
import {serverUrl} from "./links.js";

const form = document.getElementById("settings");
const input = document.getElementById("server");
const status = document.getElementById("status");
const {server = DEFAULT_SERVER} = await chrome.storage.local.get("server");
input.value = server;

form.addEventListener("submit", async event => {
  event.preventDefault();
  const url = serverUrl(input.value);
  if (!url) {
    status.textContent = "请输入服务的 HTTPS 根地址，可以包含端口。";
    return;
  }
  await chrome.storage.local.set({server: url.href});
  input.value = url.href;
  status.textContent = "已保存。";
});
