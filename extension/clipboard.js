// Use the offscreen clipboard pattern from GoogleChrome/chrome-extensions-samples.
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message?.target !== "clipboard" || sender.id !== chrome.runtime.id ||
      typeof message.text !== "string" || !message.text) return;
  const input = document.getElementById("clipboard");
  input.value = message.text;
  input.select();
  try {
    respond({copied: document.execCommand("copy")});
  } finally {
    input.value = "";
  }
});
