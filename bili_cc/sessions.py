from contextlib import closing, contextmanager
import hashlib
from pathlib import Path
import secrets
import sqlite3
import time


SESSION_COOKIE = "__Host-bilicc_session"
QR_COOKIE = "__Host-bilicc_qr"
SESSION_TTL = 30 * 24 * 60 * 60
QR_TTL = 180
POLL_INTERVAL = 2
TOKEN_LENGTH = 43


def digest(token):
    if token and len(token) == TOKEN_LENGTH and token.isascii():
        return hashlib.sha256(token.encode("ascii")).hexdigest()
    return ""


class Sessions:
    def __init__(self, directory: Path):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.path = directory / "sessions.sqlite3"
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    sessdata TEXT NOT NULL,
                    expires INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS challenges (
                    id TEXT PRIMARY KEY,
                    qr_key TEXT NOT NULL,
                    expires INTEGER NOT NULL,
                    next_poll INTEGER NOT NULL DEFAULT 0
                );
            """)
        self.path.chmod(0o600)

    @contextmanager
    def connect(self):
        with closing(sqlite3.connect(self.path, timeout=1)) as db, db:
            db.execute("PRAGMA secure_delete=ON")
            yield db

    def purge(self):
        now = int(time.time())
        with self.connect() as db:
            db.execute("DELETE FROM sessions WHERE expires <= ?", (now,))
            db.execute("DELETE FROM challenges WHERE expires <= ?", (now,))

    def credential(self, token):
        if not digest(token):
            return None
        with self.connect() as db:
            row = db.execute(
                "SELECT sessdata FROM sessions WHERE id = ? AND expires > ?",
                (digest(token), int(time.time())),
            ).fetchone()
        return row[0] if row else None

    def start_challenge(self, key, previous_token):
        token = secrets.token_urlsafe(32)
        with self.connect() as db:
            db.execute("DELETE FROM challenges WHERE id = ?", (digest(previous_token),))
            db.execute(
                "INSERT INTO challenges (id, qr_key, expires) VALUES (?, ?, ?)",
                (digest(token), key, int(time.time()) + QR_TTL),
            )
        return token

    def reserve_poll(self, token):
        identifier = digest(token)
        if not identifier:
            return "expired", None
        now = int(time.time())
        with self.connect() as db:
            updated = db.execute(
                "UPDATE challenges SET next_poll = ? WHERE id = ? AND expires > ? AND next_poll <= ? "
                "RETURNING qr_key",
                (now + POLL_INTERVAL, identifier, now, now),
            ).fetchone()
            if updated:
                return "poll", updated[0]
            active = db.execute("SELECT 1 FROM challenges WHERE id = ? AND expires > ?", (identifier, now)).fetchone()
        return ("slow_down" if active else "expired"), None

    def complete(self, token, key, old_session, sessdata, upstream_expiry):
        new_token = secrets.token_urlsafe(32)
        now = int(time.time())
        expires = min(now + SESSION_TTL, upstream_expiry or now + SESSION_TTL)
        if expires <= now:
            return None
        with self.connect() as db:
            consumed = db.execute(
                "DELETE FROM challenges WHERE id = ? AND qr_key = ? AND expires > ?",
                (digest(token), key, now),
            ).rowcount
            if not consumed:
                return None
            db.execute("DELETE FROM sessions WHERE id = ?", (digest(old_session),))
            db.execute(
                "INSERT INTO sessions (id, sessdata, expires) VALUES (?, ?, ?)",
                (digest(new_token), sessdata, expires),
            )
        return new_token, expires - now

    def clear(self, session, challenge=None):
        with self.connect() as db:
            db.execute("DELETE FROM sessions WHERE id = ?", (digest(session),))
            if challenge:
                db.execute("DELETE FROM challenges WHERE id = ?", (digest(challenge),))
