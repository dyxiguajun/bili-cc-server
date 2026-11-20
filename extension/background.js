import {DEFAULT_SERVER} from "./default-server.js";
import {serverUrl, shareableVideo} from "./links.js";

chrome.action.onClicked.addListener(async tab => {
  const {server = DEFAULT_SERVER} = await chrome.storage.local.get("server");
  const target = serverUrl(server);
  if (!target) {
    await chrome.runtime.openOptionsPage();
    return;
  }

  const video = shareableVideo(tab.url);
  if (video) target.hash = new URLSearchParams({video}).toString();
  await chrome.tabs.create({url: target.href});
});
