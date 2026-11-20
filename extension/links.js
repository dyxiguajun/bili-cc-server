const videoHosts = new Set(["www.bilibili.com", "bilibili.com", "m.bilibili.com"]);
const shortHosts = new Set(["b23.tv", "bili2233.cn"]);

export function serverUrl(value) {
  try {
    const url = new URL(value.trim());
    if (url.protocol === "https:" && !url.username && !url.password && !url.search && !url.hash && url.pathname === "/") {
      return url;
    }
  } catch {
    return null;
  }
  return null;
}

export function shareableVideo(value) {
  try {
    const current = new URL(value);
    if (!["http:", "https:"].includes(current.protocol) || current.port || current.username || current.password) return null;
    const video = videoHosts.has(current.hostname) && /^\/video\/BV[A-Za-z0-9]{10}\/?$/.test(current.pathname);
    const short = shortHosts.has(current.hostname) && /^\/[A-Za-z0-9]{1,40}\/?$/.test(current.pathname);
    if (!video && !short) return null;
    const clean = new URL(current.pathname, "https://" + current.hostname);
    const page = current.searchParams.get("p");
    if (video && page && /^[1-9][0-9]{0,3}$/.test(page)) clean.searchParams.set("p", page);
    return clean.href;
  } catch {
    return null;
  }
}
