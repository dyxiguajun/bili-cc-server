import asyncio
import base64
from contextlib import asynccontextmanager, suppress
import io
import json
import logging
import os
from pathlib import Path
import re

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.trustedhost import TrustedHostMiddleware

import qrcode

from . import VERSION
from .bili import Bilibili, ServiceError, http_client
from .sessions import QR_COOKIE, QR_TTL, SESSION_COOKIE, Sessions


ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "web"
MAX_REQUEST_BYTES = 8192
POST_HEADER = "x-bili-client"
EXTENSION_ORIGIN = "chrome-extension://jpopdalnimaeaeblpammpjheebipbooj"
EXTENSION_POST_PATHS = {"/api/extract", "/api/login/start", "/api/login/poll", "/api/logout"}
LOG = logging.getLogger("uvicorn.error")
COOKIE_FLAGS = {"path": "/", "secure": True, "httponly": True, "samesite": "strict"}


async def purge_expired(sessions):
    while True:
        await asyncio.sleep(600)
        sessions.purge()


def error_response(error):
    headers = {"Retry-After": str(error.retry_after)} if error.retry_after else None
    return JSONResponse(
        {"status": error.code, "message": error.message},
        status_code=error.status,
        headers=headers,
    )


async def extract_input(request):
    collected = bytearray()
    async for chunk in request.stream():
        collected.extend(chunk)
        if len(collected) > MAX_REQUEST_BYTES:
            raise ServiceError(413, "input_too_large", "输入超过服务上限。")
    try:
        body = json.loads(collected)
        value = body.get("url")
        track = body.get("track")
        language = body.get("language")
        if (
            not isinstance(value, str)
            or not 1 <= len(value.strip()) <= 2048
            or (track is not None and (not isinstance(track, str) or not re.fullmatch(r"[0-9]{1,25}", track)))
            or language not in (None, "zh", "en", "ja")
        ):
            raise ValueError
    except (ValueError, AttributeError, RecursionError):
        raise ServiceError(400, "invalid_input", "请输入有效的视频链接、BV 号和字幕轨。") from None
    return value, track, language


def allowed_post(request):
    if request.headers.get(POST_HEADER) != "web":
        return False
    origin = request.headers.get("origin")
    if origin == EXTENSION_ORIGIN:
        return request.url.path in EXTENSION_POST_PATHS
    host = request.headers.get("host", "")
    allowed = {f"https://{host}"}
    if host.split(":")[0] in {"localhost", "127.0.0.1"}:
        allowed.add(f"http://{host}")
    return (origin is None or origin in allowed) and request.headers.get("sec-fetch-site") != "cross-site"


def create_app(data_dir=None, transport=None):
    @asynccontextmanager
    async def lifespan(app):
        os.umask(0o077)
        directory = data_dir or Path(os.environ.get("BILI_DATA_DIR", ROOT / "data"))
        app.state.sessions = Sessions(directory)
        app.state.sessions.purge()
        app.state.slots = asyncio.Semaphore(6)
        async with http_client(transport) as client:
            app.state.upstream = Bilibili(client)
            cleanup = asyncio.create_task(purge_expired(app.state.sessions))
            try:
                yield
            finally:
                cleanup.cancel()
                with suppress(asyncio.CancelledError):
                    await cleanup

    app = FastAPI(
        title="Bili CC Server", version=VERSION,
        docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan,
    )
    hosts = ["localhost", "127.0.0.1", "testserver"]
    if host := os.environ.get("BILI_PUBLIC_HOST"):
        hosts.append(host)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=hosts)

    @app.middleware("http")
    async def protect_api(request, call_next):
        try:
            if request.method == "POST" and request.url.path.startswith("/api/") and not allowed_post(request):
                raise ServiceError(403, "forbidden", "请从字幕提取页面发起请求。")
            response = await call_next(request)
        except ServiceError as error:
            response = error_response(error)
        except Exception as error:
            LOG.error("Request failed (%s)", type(error).__name__)
            response = error_response(ServiceError(500, "service_error", "服务暂时出错，请稍后再试。"))
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; img-src 'self' data:; object-src 'none'; "
            "base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        )
        return response

    @app.exception_handler(ServiceError)
    async def handle_service_error(request, error):
        response = error_response(error)
        if error.code == "login_required":
            app.state.sessions.clear(request.cookies.get(SESSION_COOKIE))
            response.delete_cookie(SESSION_COOKIE, **COOKIE_FLAGS)
        return response

    @asynccontextmanager
    async def upstream_slot():
        if app.state.slots.locked():
            raise ServiceError(503, "busy", "服务正在处理其他请求，请稍后再试。", 3)
        try:
            async with app.state.slots, asyncio.timeout(20):
                yield app.state.upstream
        except TimeoutError:
            raise ServiceError(504, "timeout", "获取 B 站数据超时，请稍后再试。") from None

    @app.get("/healthz")
    async def health():
        with app.state.sessions.connect() as db:
            db.execute("SELECT 1 FROM sessions LIMIT 1").fetchone()
        return {"status": "ok", "version": VERSION}

    @app.get("/")
    async def index():
        if (STATIC / "index.html").is_file():
            return FileResponse(STATIC / "index.html")
        return {"name": "Bili CC Server", "version": VERSION}

    app.mount("/static", StaticFiles(directory=STATIC), name="static")

    @app.get("/api/session")
    async def session(request: Request):
        credential = app.state.sessions.credential(request.cookies.get(SESSION_COOKIE))
        return {"signed_in": credential is not None}

    @app.post("/api/extract")
    async def extract(request: Request):
        link, selected, language = await extract_input(request)
        credential = app.state.sessions.credential(request.cookies.get(SESSION_COOKIE))
        async with upstream_slot() as upstream:
            return await upstream.extract(link, selected, credential, language)

    @app.post("/api/login/start")
    async def login_start(request: Request):
        async with upstream_slot() as upstream:
            key, url = await upstream.qr_start()
        image = io.BytesIO()
        qrcode.make(url).save(image, format="PNG")
        token = app.state.sessions.start_challenge(key, request.cookies.get(QR_COOKIE))
        response = JSONResponse({
            "url": url,
            "image": "data:image/png;base64," + base64.b64encode(image.getvalue()).decode("ascii"),
            "expires_in": QR_TTL,
        })
        response.set_cookie(QR_COOKIE, token, max_age=QR_TTL, **COOKIE_FLAGS)
        return response

    @app.post("/api/login/poll")
    async def login_poll(request: Request):
        token = request.cookies.get(QR_COOKIE)
        async with upstream_slot() as upstream:
            status, key = app.state.sessions.reserve_poll(token)
            if status != "poll":
                return {"status": status}
            status, credential = await upstream.qr_poll(key)
        if not credential:
            if status == "expired":
                app.state.sessions.clear(None, token)
            return {"status": status}
        session = app.state.sessions.complete(token, key, request.cookies.get(SESSION_COOKIE), *credential)
        if not session:
            return {"status": "expired"}
        session_token, max_age = session
        response = JSONResponse({"status": "signed_in"})
        response.set_cookie(SESSION_COOKIE, session_token, max_age=max_age, **COOKIE_FLAGS)
        response.delete_cookie(QR_COOKIE, **COOKIE_FLAGS)
        return response

    @app.post("/api/logout")
    async def logout(request: Request):
        app.state.sessions.clear(request.cookies.get(SESSION_COOKIE), request.cookies.get(QR_COOKIE))
        response = JSONResponse({"signed_in": False})
        response.delete_cookie(SESSION_COOKIE, **COOKIE_FLAGS)
        response.delete_cookie(QR_COOKIE, **COOKIE_FLAGS)
        return response

    return app


app = create_app()
