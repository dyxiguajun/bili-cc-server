from contextlib import AsyncExitStack
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import httpx

from bili_cc.app import EXTENSION_ORIGIN, create_app


BV = "BV1BbKw6XEWq"
HEADERS = {"X-Bili-Client": "web", "Origin": "https://localhost"}
PRIVATE_COOKIE = "test-session-not-a-real-credential"
TRACK = "9812345678912345678"


class ServiceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.calls = []
        self.stack = AsyncExitStack()
        self.addAsyncCleanup(self.stack.aclose)
        self.app = await self.start_app()

    async def start_app(self):
        app = create_app(Path(self.temp.name), httpx.MockTransport(self.upstream))
        await self.stack.enter_async_context(app.router.lifespan_context(app))
        return app

    async def browser(self, app=None, cookies=None):
        return await self.stack.enter_async_context(httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app or self.app),
            base_url="https://localhost", headers=HEADERS, cookies=cookies,
        ))

    def upstream(self, request):
        host, path = request.url.host, request.url.path
        credentials = request.headers.get("cookie")
        self.calls.append((host, path, credentials))
        if host == "b23.tv":
            link = (
                "https://example.invalid/video/" + BV if path == "/other"
                else "https://www.bilibili.com/video/" + BV + "?p=2"
            )
            return httpx.Response(302, headers={"Location": link})
        if host == "www.bilibili.com":
            metadata = {
                "videoData": {
                    "bvid": BV, "aid": 18, "title": "字幕验收样例",
                    "pages": [{"cid": 101, "duration": 11, "part": "一"},
                              {"cid": 102, "duration": 30, "part": "二"}],
                },
            }
            page = f"<script>window.__INITIAL_STATE__={json.dumps(metadata)};</script>"
            if getattr(self, "large_video_page", False):
                page += "x" * (9 * 1024 * 1024)
            return httpx.Response(200, text=page)
        if host == "passport.bilibili.com" and path.endswith("/generate"):
            return httpx.Response(200, json={
                "code": 0,
                "data": {
                    "qrcode_key": "fixture-qr-key",
                    "url": "https://account.bilibili.com/fixture?qr=fixture-qr-key",
                },
            })
        if host == "passport.bilibili.com" and path.endswith("/poll"):
            return httpx.Response(
                200,
                json={"code": 0, "data": {"code": 0}},
                headers={"Set-Cookie": f"SESSDATA={PRIVATE_COOKIE}; Domain=.bilibili.com; Path=/; Max-Age=3600; Secure"},
            )
        if host == "api.bilibili.com" and path in {"/x/player/v2", "/x/player/wbi/v2"}:
            result = {"need_login_subtitle": True, "cid": request.url.params["cid"]}
            if credentials == f"SESSDATA={PRIVATE_COOKIE}":
                cid = request.url.params["cid"]
                if getattr(self, "wrong_video", False) and path == "/x/player/wbi/v2":
                    cid = "999"
                ai_url = f"//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/18{cid}{'a' * 32}?auth=signed"
                result["subtitle"] = {"subtitles": [
                    {"id_str": TRACK, "lan": "ai-zh", "lan_doc": "中文",
                     "subtitle_url": ai_url},
                    {"id_str": "2", "lan": "en", "lan_doc": "English",
                     "subtitle_url": "//aisubtitle.hdslb.com/bfs/subtitle/fixture-en.json?auth=signed"},
                ]}
                if getattr(self, "english_first", False):
                    result["subtitle"]["subtitles"].reverse()
                if getattr(self, "english_only", False):
                    result["subtitle"]["subtitles"] = [
                        track for track in result["subtitle"]["subtitles"] if track["lan"] == "en"
                    ]
            return httpx.Response(200, json={"code": 0, "data": result})
        if host == "aisubtitle.hdslb.com":
            self.assertEqual(request.url.query, b"auth=signed")
            return httpx.Response(200, json={"body": [
                {"from": 0.125, "to": 1.5, "content": "First line" if "fixture-en.json" in path else "第一行"},
                {"from": 2.0, "to": 3.0, "content": "第二行"},
            ]})
        self.fail(f"Unexpected upstream destination: {host}{path}")

    async def log_in(self, browser):
        start = await browser.post("/api/login/start")
        self.assertEqual(start.status_code, 200)
        self.assertIn("data:image/png;base64,", start.json()["image"])
        self.assertNotIn("qrcode_key", start.json())
        self.assertIn("HttpOnly", start.headers["set-cookie"])
        self.assertIn("Secure", start.headers["set-cookie"])
        poll = await browser.post("/api/login/poll")
        self.assertEqual(poll.json(), {"status": "signed_in"})
        self.assertNotIn(PRIVATE_COOKIE, str(poll.headers))
        self.assertEqual((await browser.get("/api/session")).json(), {"signed_in": True})

    async def test_qr_extraction_isolated_and_persistent(self):
        browser, other = await self.browser(), await self.browser()
        page = {"url": "【样例】 https://b23.tv/fixture"}
        self.assertEqual((await other.post("/api/login/poll")).json()["status"], "expired")
        self.assertEqual((await other.post("/api/extract", json=page)).json()["status"], "login_required")
        await self.log_in(browser)
        self.assertEqual((await other.get("/api/session")).json(), {"signed_in": False})
        data = (await browser.post("/api/extract", json=page)).json()
        self.assertEqual((data["status"], data["video"]["page"], data["selected"]), ("ok", 2, TRACK))
        self.assertEqual(data["text"], "第一行\n第二行")
        self.assertIn("00:00:00,125 --> 00:00:01,500", data["srt"])
        self.assertEqual([call[0] for call in self.calls if call[2]], ["api.bilibili.com"])
        self.assertEqual((await other.post("/api/extract", json=page)).json()["status"], "login_required")

        saved_cookies = httpx.Cookies(browser.cookies)
        await self.stack.aclose()
        restarted = await self.browser(await self.start_app(), cookies=saved_cookies)
        self.assertTrue((await restarted.get("/api/session")).json()["signed_in"])
        await restarted.post("/api/logout")
        self.assertFalse((await restarted.get("/api/session")).json()["signed_in"])
        replay = await self.browser(cookies=saved_cookies)
        self.assertFalse((await replay.get("/api/session")).json()["signed_in"])

    async def test_track_selection_and_missing_page(self):
        browser = await self.browser()
        await self.log_in(browser)
        response = await browser.post("/api/extract", json={"url": BV, "track": "2"})
        self.assertEqual(response.json()["selected"], "2")
        self.assertTrue(response.json()["text"].startswith("First line"))
        response = await browser.post("/api/extract", json={"url": BV, "track": "3"})
        self.assertEqual(response.json()["status"], "track_unavailable")
        response = await browser.post("/api/extract", json={"url": f"https://www.bilibili.com/video/{BV}/?p=99"})
        self.assertEqual(response.json()["status"], "page_unavailable")

    async def test_foreign_ai_caption_is_rejected_before_fetch(self):
        browser = await self.browser()
        await self.log_in(browser)
        self.wrong_video = True
        response = await browser.post("/api/extract", json={"url": BV})
        self.assertEqual(response.json()["status"], "ok")
        destinations = [path for host, path, _ in self.calls if host == "aisubtitle.hdslb.com"]
        self.assertEqual(destinations, ["/bfs/ai_subtitle/prod/18101" + "a" * 32])

    async def test_share_prefers_chinese_and_ignores_large_page_tail(self):
        browser = await self.browser()
        await self.log_in(browser)
        self.large_video_page = True
        self.english_first = True
        request = {"url": "中文标题 https://b23.tv/fixture", "language": "zh"}
        reply = (await browser.post("/api/extract", json=request)).json()
        self.assertEqual((reply["selected"], reply["text"]), (TRACK, "第一行\n第二行"))
        self.english_only = True
        reply = (await browser.post("/api/extract", json=request)).json()
        self.assertEqual(reply["status"], "no_chinese_subtitle")
        self.assertNotIn("text", reply)

    async def test_rejects_cross_origin_and_unsupported_links(self):
        browser = await self.browser()
        denied = await browser.post("/api/login/start", headers={"Origin": "https://example.invalid"})
        self.assertEqual(denied.status_code, 403)
        denied = await browser.post("/api/logout", headers={"X-Bili-Client": "bad"})
        self.assertEqual(denied.status_code, 403)
        bad_link = await browser.post("/api/extract", json={"url": f"https://example.invalid/video/{BV}/"})
        self.assertEqual(bad_link.status_code, 400)
        self.assertEqual(self.calls, [])
        bad_redirect = await browser.post("/api/extract", json={"url": "https://b23.tv/other"})
        self.assertEqual(bad_redirect.status_code, 400)
        self.assertEqual(self.calls, [("b23.tv", "/other", None)])

    async def test_only_bili_cc_extension_can_request_its_language(self):
        browser = await self.browser()
        await self.log_in(browser)
        payload = {"url": BV, "language": "en"}
        headers = {"Origin": EXTENSION_ORIGIN, "Sec-Fetch-Site": "cross-site"}
        reply = (await browser.post("/api/extract", json=payload, headers=headers)).json()
        self.assertEqual(reply["selected"], "2")
        self.assertTrue(reply["text"].startswith("First line"))
        missing = await browser.post("/api/extract", json={**payload, "language": "ja"}, headers=headers)
        self.assertEqual(missing.json()["status"], "no_language_subtitle")
        self.assertEqual((await browser.post("/api/logout", headers=headers)).status_code, 403)
        headers["Origin"] = "chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        blocked = await browser.post("/api/extract", json=payload, headers=headers)
        self.assertEqual(blocked.status_code, 403)
