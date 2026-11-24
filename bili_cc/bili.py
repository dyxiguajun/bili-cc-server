import asyncio
from dataclasses import dataclass
from http.cookiejar import CookieJar, DefaultCookiePolicy
import json
import math
import re
import time
from urllib.parse import parse_qs, urljoin, urlsplit, urlunsplit

import httpx


UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
WEB = "https://www.bilibili.com"
API = "https://api.bilibili.com"
PASSPORT = "https://passport.bilibili.com/x/passport-login/web/qrcode"
BV = re.compile(r"BV[A-Za-z0-9]{10}")
VIDEO_PATH = re.compile(r"/video/(BV[A-Za-z0-9]{10})/?")
WEB_HOSTS = {"bilibili.com", "www.bilibili.com", "m.bilibili.com"}
SHORT_HOSTS = {"b23.tv", "bili2233.cn"}
REDIRECTS = {301, 302, 303, 307, 308}
MAX_VIDEO_BYTES = 8 * 1024 * 1024
VIDEO_STATE = re.compile(rb"window\.__INITIAL_STATE__\s*=\s*")
MAX_SUBTITLE_BYTES = 3 * 1024 * 1024
MAX_API_BYTES = 1024 * 1024
COOKIE_VALUE = re.compile(r"[A-Za-z0-9%_=.+/\-]{1,1024}")
PLAYER_PATHS = ("/x/player/wbi/v2", "/x/player/v2")
RECOVERABLE_SUBTITLE_ERRORS = {
    "upstream_format", "unexpected_cdn", "subtitle_video_mismatch", "invalid_subtitle", "short_subtitle",
}


class ServiceError(Exception):
    def __init__(self, status, code, message, retry_after=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retry_after = retry_after


@dataclass(frozen=True)
class Video:
    bvid: str
    page: int = 1

    @property
    def url(self):
        return f"{WEB}/video/{self.bvid}/?p={self.page}"


class RejectCookies(DefaultCookiePolicy):
    def set_ok(self, cookie, request):
        return False


def http_client(transport=None):
    return httpx.AsyncClient(
        transport=transport,
        cookies=CookieJar(policy=RejectCookies()),
        trust_env=False,
        follow_redirects=False,
        timeout=httpx.Timeout(6, connect=4, pool=1),
        limits=httpx.Limits(max_connections=12, max_keepalive_connections=6),
        headers={"User-Agent": UA, "Accept-Encoding": "gzip, deflate"},
    )


def invalid_link():
    return ServiceError(400, "invalid_link", "请输入 B 站视频链接、b23.tv 短链接或 BV 号。")


def valid_url(url, protocols=("https",)):
    try:
        parts = urlsplit(url)
        if (
            parts.scheme not in protocols
            or not parts.hostname
            or parts.username is not None
            or parts.password is not None
            or parts.port is not None
            or "\\" in url
            or any(ord(char) <= 32 or ord(char) == 127 for char in url)
        ):
            raise ValueError
        return parts
    except ValueError as exc:
        raise invalid_link() from exc


def parse_video(parts):
    match = VIDEO_PATH.fullmatch(parts.path)
    if parts.hostname not in WEB_HOSTS or not match:
        raise invalid_link()
    raw_page = parse_qs(parts.query).get("p", ["1"])[0]
    if not re.fullmatch(r"[0-9]{1,4}", raw_page) or int(raw_page) < 1:
        raise ServiceError(400, "invalid_page", "分 P 编号无效，请检查链接中的 p 参数。")
    return Video(match[1], int(raw_page))


def subtitle_url(raw, aid, cid):
    try:
        if raw.startswith("//"):
            raw = "https:" + raw
        elif raw.startswith("http://"):
            raw = "https://" + raw[7:]
        parts = valid_url(raw)
        allowed = parts.hostname in {"aisubtitle.hdslb.com", "subtitle.hdslb.com", "subtitle.bilibili.com"}
        image_cdn = re.fullmatch(r"i[0-9]+\.hdslb\.com", parts.hostname)
        if (allowed or image_cdn) and parts.path.startswith(("/bfs/subtitle/", "/bfs/ai_subtitle/")):
            checked = parts.path
        else:
            checked = None
    except (AttributeError, ServiceError):
        checked = None
    if checked is None:
        raise ServiceError(502, "unexpected_cdn", "B 站返回了无法识别的字幕地址。")
    if checked.startswith("/bfs/ai_subtitle/prod/"):
        # Production AI captions encode the video and part IDs before the 32-digit digest.
        expected = rf"/bfs/ai_subtitle/prod/{aid}{cid}[0-9a-fA-F]{{32}}(?:\.json)?"
        if not re.fullmatch(expected, checked):
            raise ServiceError(502, "subtitle_video_mismatch", "B 站返回的 AI 字幕与当前视频分 P 不匹配。")
    return raw


def upstream_format():
    return ServiceError(502, "upstream_format", "B 站返回了无法识别的数据，请稍后再试。")


def preferred_chinese_track(tracks):
    candidates = []
    for track in tracks:
        raw = track["language"].lower()
        language = raw.removeprefix("ai-")
        if language == "zh" or language.startswith("zh-") or language in {"cmn", "yue"}:
            priority = (language not in {"zh", "zh-cn", "zh-hans", "zh-sg", "cmn"}, raw.startswith("ai-"))
            candidates.append((priority, track["id"]))
    return min(candidates, key=lambda item: item[0])[1] if candidates else None


def read_json(body):
    try:
        data = json.loads(body)
        if isinstance(data, dict):
            return data
    except (ValueError, RecursionError):
        pass
    raise upstream_format()


def api_data(data):
    code = data.get("code")
    if code == 0 and isinstance(data.get("data"), dict):
        return data["data"]
    if code in {-404, 62002, 62004}:
        raise ServiceError(404, "video_unavailable", "这个视频已失效或当前无法访问。")
    if code == -101:
        raise ServiceError(401, "login_required", "请登录 B 站后重试。")
    if code in {-412, -352, -509}:
        raise ServiceError(503, "upstream_limited", "B 站暂时限制了请求，请稍后再试。", 20)
    raise upstream_format()


def srt_time(seconds):
    milliseconds = round(seconds * 1000)
    total_seconds, ms = divmod(milliseconds, 1000)
    minutes, sec = divmod(total_seconds, 60)
    hours, minute = divmod(minutes, 60)
    return f"{hours:02d}:{minute:02d}:{sec:02d},{ms:03d}"


def render_subtitles(data, duration, *, ai=False):
    # Reuse the desktop version's plain text and SRT behavior, with one CDN response for both.
    body = data.get("body")
    if not isinstance(body, list) or len(body) > 20_000:
        raise upstream_format()
    lines, cues = [], []
    last_end = 0.0
    last_allowed = max(duration * 1.05, duration + 10) if duration > 0 else 604_800
    try:
        for item in body:
            text = (item.get("content") or "").strip()
            if not text:
                continue
            start, end = float(item["from"]), float(item["to"])
            if not (math.isfinite(start) and math.isfinite(end) and 0 <= start <= end <= last_allowed):
                raise ValueError
            lines.append(text)
            cues.append(f"{len(lines)}\n{srt_time(start)} --> {srt_time(end)}\n{text}\n")
            last_end = max(last_end, end)
    except (AttributeError, KeyError, TypeError, ValueError) as exc:
        raise ServiceError(502, "invalid_subtitle", "字幕时间与视频分 P 不一致，或字幕格式异常。") from exc
    if ai and duration >= 120 and lines and last_end < duration * 0.35:
        raise ServiceError(502, "short_subtitle", "B 站目前只返回了一小段 AI 字幕，请稍后重新提取。")
    return "\n".join(lines), "\n".join(cues)


class Bilibili:
    def __init__(self, client):
        self.client = client
        self.api_backoff_until = 0.0

    async def get(self, url, *, params=None, referer=WEB + "/", sessdata=None, limit=MAX_API_BYTES,
                  short=False, video_state=False):
        parts = urlsplit(url)
        host = parts.hostname
        if host == "api.bilibili.com" and time.monotonic() < self.api_backoff_until:
            raise ServiceError(503, "upstream_limited", "B 站暂时限制了请求，请稍后再试。", 20)
        headers = {"Referer": referer}
        if sessdata and host == "api.bilibili.com" and parts.path in (*PLAYER_PATHS, "/x/web-interface/nav"):
            headers["Cookie"] = f"SESSDATA={sessdata}"
        for attempt in range(2):
            try:
                async with self.client.stream("GET", url, params=params, headers=headers) as response:
                    status = response.status_code
                    if short and status in REDIRECTS:
                        return response, b""
                    if status in {412, 429}:
                        if host == "api.bilibili.com":
                            self.api_backoff_until = time.monotonic() + 20
                        raise ServiceError(503, "upstream_limited", "B 站暂时限制了请求，请稍后再试。", 20)
                    if status == 404 and host in WEB_HOSTS:
                        raise ServiceError(404, "video_unavailable", "这个 B 站视频已失效或无法访问。")
                    if status >= 500 and attempt == 0:
                        await asyncio.sleep(0.25)
                        continue
                    if status != 200:
                        raise ServiceError(502, "upstream_http", "B 站暂时无法提供该数据，请稍后再试。")
                    body = bytearray()
                    state_start = None
                    async for chunk in response.aiter_bytes(chunk_size=65_536):
                        body.extend(chunk)
                        if video_state:
                            if state_start is None:
                                marker = VIDEO_STATE.search(body, 0, limit)
                                if marker:
                                    state_start = marker.end()
                            if state_start is not None:
                                end = body.find(b"</script>", state_start, limit)
                                if end != -1:
                                    return response, body[state_start:end]
                        if len(body) > limit:
                            raise ServiceError(502, "upstream_too_large", "B 站返回的数据超过了服务上限。")
                    if video_state:
                        if state_start is None:
                            raise upstream_format()
                        return response, body[state_start:]
                    return response, body
            except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError):
                if attempt == 0:
                    await asyncio.sleep(0.25)
                    continue
                raise ServiceError(503, "upstream_network", "连接 B 站超时或中断，请稍后再试。") from None
            except httpx.DecodingError:
                raise upstream_format() from None

    async def resolve(self, value):
        value = value.strip()
        if BV.fullmatch(value):
            return Video(value)
        match = re.search(r"https?://[^\s<>「」【】]+", value)
        if not match:
            raise invalid_link()
        parts = valid_url(match.group().rstrip("。，、；！）)"), ("http", "https"))
        if parts.hostname not in SHORT_HOSTS:
            return parse_video(parts)
        for _ in range(4):
            if not re.fullmatch(r"/[A-Za-z0-9]{1,40}/?", parts.path):
                raise invalid_link()
            short_url = urlunsplit(("https", parts.hostname, parts.path, "", ""))
            response, _ = await self.get(short_url, limit=65_536, short=True)
            location = response.headers.get("location")
            if response.status_code not in REDIRECTS or not location:
                break
            parts = valid_url(urljoin(short_url, location), ("http", "https"))
            if parts.hostname not in SHORT_HOSTS:
                return parse_video(parts)
        raise ServiceError(400, "short_link_unavailable", "这个短链接无法解析，请粘贴原视频链接。")

    async def video_info(self, video):
        # The watch page supplies metadata even where the legacy view API is unavailable.
        _, body = await self.get(video.url, limit=MAX_VIDEO_BYTES, video_state=True)
        try:
            state_data, _ = json.JSONDecoder().raw_decode(body.decode("utf-8").lstrip())
            data = state_data.get("videoData") or {}
            if not data:
                raise ServiceError(404, "video_unavailable", "这个 B 站视频已失效或无法访问。")
            if data.get("bvid") != video.bvid:
                raise upstream_format()
            pages = data["pages"]
            if video.page > len(pages):
                raise ServiceError(404, "page_unavailable", f"这条视频有 {len(pages)} 个分 P。")
            part = pages[video.page - 1]
            return {
                "bvid": video.bvid,
                "page": video.page,
                "url": video.url,
                "title": str(data["title"])[:500],
                "part": str(part.get("part", ""))[:300] if len(pages) > 1 else "",
                "aid": int(data["aid"]),
                "cid": int(part["cid"]),
                "duration": int(part.get("duration", 0)),
            }
        except (AttributeError, IndexError, KeyError, TypeError, ValueError, RecursionError) as exc:
            raise upstream_format() from exc

    async def extract(self, query, selected, sessdata, language=None):
        video = await self.resolve(query)
        info = await self.video_info(video)
        error = None
        try:
            result = await self.fetch_player_subtitle(PLAYER_PATHS[0], video, info, selected, sessdata, language)
            if not sessdata or result["status"] not in {"no_subtitle", "track_unavailable", "no_chinese_subtitle"}:
                return result
        except ServiceError as exc:
            if exc.code not in RECOVERABLE_SUBTITLE_ERRORS:
                raise
            error = exc

        retry = await self.fetch_player_subtitle(PLAYER_PATHS[1], video, info, selected, sessdata, language)
        if error and retry["status"] != "ok":
            raise error
        if not error and result["status"] == "no_chinese_subtitle" and retry["status"] == "no_subtitle":
            return result
        return retry

    async def fetch_player_subtitle(self, path, video, info, selected, sessdata, language):
        _, body = await self.get(
            API + path,
            params={"bvid": info["bvid"], "aid": info["aid"], "cid": info["cid"]},
            referer=video.url,
            sessdata=sessdata,
        )
        player = api_data(read_json(body))
        if player.get("bvid") not in (None, video.bvid) or str(player.get("cid", info["cid"])) != str(info["cid"]):
            raise upstream_format()
        subtitles = (player.get("subtitle") or {}).get("subtitles") or []
        if not isinstance(subtitles, list) or len(subtitles) > 100:
            raise upstream_format()
        tracks = []
        urls = {}
        for track in subtitles:
            tid = str(track.get("id_str") or track.get("id") or "")
            url = track.get("subtitle_url")
            if not re.fullmatch(r"[0-9]{1,25}", tid) or not url or tid in urls:
                continue
            tracks.append({"id": tid, "language": str(track.get("lan") or "")[:20],
                           "label": str(track.get("lan_doc") or track.get("lan") or "字幕")[:80]})
            urls[tid] = url
        res = {"video": {key: value for key, value in info.items() if key not in {"aid", "cid"}}, "tracks": tracks}
        if not tracks:
            if player.get("need_login_subtitle"):
                if not sessdata:
                    return {**res, "status": "login_required"}
                if not await self.check_session(sessdata):
                    raise ServiceError(401, "login_required", "B 站登录已过期，请重新扫码。")
            return {**res, "status": "no_subtitle"}
        if selected is not None and selected not in urls:
            return {**res, "status": "track_unavailable"}
        chosen = selected
        if chosen is None:
            chosen = preferred_chinese_track(tracks) if language == "zh" else tracks[0]["id"]
        if chosen is None:
            return {**res, "status": "no_chinese_subtitle"}
        source = subtitle_url(urls[chosen], info["aid"], info["cid"])
        _, body = await self.get(source, referer=video.url, limit=MAX_SUBTITLE_BYTES)
        ai = next(track["language"].startswith("ai-") for track in tracks if track["id"] == chosen)
        text, srt = render_subtitles(read_json(body), info["duration"], ai=ai)
        if not text:
            return {**res, "status": "no_subtitle"}
        return {**res, "status": "ok", "selected": chosen, "text": text, "srt": srt}

    async def check_session(self, sessdata):
        _, body = await self.get(API + "/x/web-interface/nav", sessdata=sessdata)
        result = read_json(body)
        if result.get("code") == -101:
            return False
        return bool(api_data(result).get("isLogin"))

    async def qr_start(self):
        _, body = await self.get(PASSPORT + "/generate")
        data = api_data(read_json(body))
        key, url = data.get("qrcode_key"), data.get("url")
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_\-]{8,128}", key) or not isinstance(url, str):
            raise upstream_format()
        try:
            parts = valid_url(url)
        except ServiceError:
            raise upstream_format() from None
        if parts.hostname not in {"account.bilibili.com", "passport.bilibili.com"}:
            raise upstream_format()
        return key, url

    async def qr_poll(self, key):
        response, body = await self.get(PASSPORT + "/poll", params={"qrcode_key": key})
        code = api_data(read_json(body)).get("code")
        states = {86101: "waiting_scan", 86090: "waiting_confirm", 86038: "expired"}
        if code in states:
            return states[code], None
        if code == 0:
            for cookie in response.cookies.jar:
                if cookie.name == "SESSDATA" and COOKIE_VALUE.fullmatch(cookie.value or ""):
                    return "signed_in", (cookie.value, cookie.expires)
        raise ServiceError(502, "login_response", "B 站登录响应异常，请重新生成二维码。")
