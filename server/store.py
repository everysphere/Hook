"""Free-generation allowance persistence.

Variant A monetization: every install gets a small LIFETIME free allowance of
generations, then has to subscribe. That means exactly one durable fact per
user — how many generations they have spent. Nothing about the screenshots or
the replies is stored here, and nothing ever should be.

SQLite is the default so local dev and a bare deploy just work. Set SUPABASE_URL
and SUPABASE_SERVICE_ROLE_KEY to get a store that survives a redeploy.
"""

import asyncio
import os
import sqlite3
import threading
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Optional

DEFAULT_SQLITE_PATH = "./allowance.db"
ALLOWANCE_TABLE = "allowance"
ONBOARDING_TABLE = "onboarding_profile"
INCREMENT_RPC = "increment_allowance"

# The onboarding answers we keep, in column order. Anything not in this tuple
# never reaches the database.
ONBOARDING_FIELDS = (
    "gender",
    "sexuality",
    "age_range",
    "looking_for",
    "style",
    "tone",
    "flirt_level",
)

# INSERT ... RETURNING landed in SQLite 3.35; older builds need a second SELECT.
_SQLITE_HAS_RETURNING = sqlite3.sqlite_version_info >= (3, 35, 0)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class AllowanceStore(ABC):
    """How many free generations has this install used?"""

    @abstractmethod
    async def get_used(self, app_user_id: str) -> int:
        """Generations spent so far. 0 for an unknown user."""

    @abstractmethod
    async def increment(self, app_user_id: str) -> int:
        """Spend one generation. Returns the new total."""

    @abstractmethod
    async def save_onboarding_profile(self, app_user_id: str, profile: dict) -> None:
        """Record a finished onboarding. Upsert: re-running it must not stack rows."""

    async def close(self) -> None:
        """Release any connections. Safe to call more than once."""
        return None


class SqliteAllowanceStore(AllowanceStore):
    """Single-connection SQLite store.

    sqlite3 is synchronous, so every statement runs on a worker thread behind a
    lock: one connection shared by all requests, never touched concurrently.
    """

    def __init__(self, path: str = DEFAULT_SQLITE_PATH):
        self.path = path
        self._lock = threading.Lock()
        directory = os.path.dirname(os.path.abspath(path)) if path != ":memory:" else ""
        if directory and not os.path.isdir(directory):
            os.makedirs(directory, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._init_schema()

    def _init_schema(self) -> None:
        with self._lock:
            if self.path != ":memory:":
                # Concurrent readers while a write is in flight.
                self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("""
                CREATE TABLE IF NOT EXISTS allowance (
                    app_user_id TEXT PRIMARY KEY,
                    used INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT
                )
            """)
            columns = ",\n                    ".join(f"{f} TEXT" for f in ONBOARDING_FIELDS)
            self._conn.execute(f"""
                CREATE TABLE IF NOT EXISTS onboarding_profile (
                    app_user_id TEXT PRIMARY KEY,
                    {columns},
                    completed_at TEXT
                )
            """)
            self._conn.commit()

    # --- sync bodies, run via asyncio.to_thread ---

    def _get_used_sync(self, app_user_id: str) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT used FROM allowance WHERE app_user_id = ?", (app_user_id,)
            ).fetchone()
        return int(row[0]) if row else 0

    def _increment_sync(self, app_user_id: str) -> int:
        now = _now_iso()
        with self._lock:
            if _SQLITE_HAS_RETURNING:
                row = self._conn.execute(
                    """
                    INSERT INTO allowance (app_user_id, used, updated_at)
                    VALUES (?, 1, ?)
                    ON CONFLICT(app_user_id) DO UPDATE
                        SET used = used + 1, updated_at = excluded.updated_at
                    RETURNING used
                    """,
                    (app_user_id, now),
                ).fetchone()
                self._conn.commit()
                return int(row[0])

            self._conn.execute(
                """
                INSERT INTO allowance (app_user_id, used, updated_at)
                VALUES (?, 1, ?)
                ON CONFLICT(app_user_id) DO UPDATE
                    SET used = used + 1, updated_at = excluded.updated_at
                """,
                (app_user_id, now),
            )
            row = self._conn.execute(
                "SELECT used FROM allowance WHERE app_user_id = ?", (app_user_id,)
            ).fetchone()
            self._conn.commit()
            return int(row[0]) if row else 0

    def _save_onboarding_sync(self, app_user_id: str, profile: dict) -> None:
        columns = ("app_user_id",) + ONBOARDING_FIELDS + ("completed_at",)
        values = (
            [app_user_id]
            + [profile.get(f) for f in ONBOARDING_FIELDS]
            + [_now_iso()]
        )
        placeholders = ", ".join("?" for _ in columns)
        # Onboarding finishes once, but a retry after a flaky network must not
        # create a second row — hence upsert on the install id.
        updates = ", ".join(
            f"{c} = excluded.{c}" for c in ONBOARDING_FIELDS + ("completed_at",)
        )
        with self._lock:
            self._conn.execute(
                f"""
                INSERT INTO onboarding_profile ({", ".join(columns)})
                VALUES ({placeholders})
                ON CONFLICT(app_user_id) DO UPDATE SET {updates}
                """,
                values,
            )
            self._conn.commit()

    async def get_used(self, app_user_id: str) -> int:
        return await asyncio.to_thread(self._get_used_sync, app_user_id)

    async def increment(self, app_user_id: str) -> int:
        return await asyncio.to_thread(self._increment_sync, app_user_id)

    async def save_onboarding_profile(self, app_user_id: str, profile: dict) -> None:
        await asyncio.to_thread(self._save_onboarding_sync, app_user_id, profile)

    async def close(self) -> None:
        await asyncio.to_thread(self._close_sync)

    def _close_sync(self) -> None:
        with self._lock:
            try:
                self._conn.close()
            except Exception:
                pass


class SupabaseAllowanceStore(AllowanceStore):
    """Durable store for production, backed by Supabase (PostgREST).

    Uses supabase-py's ASYNC client so PostgREST calls never block the event
    loop. The SDK is imported lazily so it stays an optional dependency, and if
    either the import or the first call fails we degrade to SQLite instead of
    taking the whole service down over a counter.

    Increment goes through the `increment_allowance` Postgres function (see
    supabase_schema.sql): PostgREST cannot do a read-modify-write atomically, so
    a select-then-update from here would race and hand out extra free
    generations under concurrent requests.

    Supabase intermittently returns PGRST303 ("JWT issued at future") when its
    gateway mints a short-lived JWT that PostgREST rejects under clock skew.
    That is a platform bug, not a bad key — we retry briefly, then fall back to
    SQLite so /me and /generate-replies keep answering instead of 500-ing.
    """

    def __init__(self, url: str, service_role_key: str,
                 fallback_path: Optional[str] = None):
        self.url = url
        self._key = service_role_key
        self._fallback_path = fallback_path or _sqlite_path_from_env()
        self._client = None
        self._fallback: Optional[AllowanceStore] = None
        self._init_lock = asyncio.Lock()
        self._initialized = False

    async def _ensure_ready(self) -> Optional[AllowanceStore]:
        """Connect on first use. Returns a fallback store if Supabase is out."""
        if self._initialized:
            return self._fallback

        async with self._init_lock:
            if self._initialized:
                return self._fallback
            try:
                # Lazy: keeps supabase optional for local dev / SQLite deploys.
                from supabase import create_async_client
            except Exception as e:
                print(f"[ERR] SUPABASE_URL is set but the supabase SDK is not "
                      f"installed ({e}) — falling back to SQLite. "
                      f"Install it with: pip install supabase")
                self._fallback = _new_sqlite_store(self._fallback_path)
                self._initialized = True
                return self._fallback

            try:
                self._client = await create_async_client(self.url, self._key)
                # Cheap round-trip so a bad URL/key/table surfaces at startup
                # rather than on a user's first generation.
                await self._client.table(ALLOWANCE_TABLE).select(
                    "app_user_id").limit(1).execute()
                print("[INFO] allowance store: Supabase "
                      f"({self.url}, table={ALLOWANCE_TABLE})")
            except Exception as e:
                print(f"[ERR] could not reach Supabase ({e}) — falling back to "
                      f"SQLite. Free allowances will NOT be durable. Check "
                      f"SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY and that "
                      f"supabase_schema.sql has been applied.")
                self._client = None
                self._fallback = _new_sqlite_store(self._fallback_path)

            self._initialized = True
            return self._fallback

    async def _demote_to_sqlite(self, err: Exception, op: str) -> AllowanceStore:
        """Stop using Supabase for this process after a hard failure."""
        if self._fallback is None:
            print(f"[ERR] Supabase {op} failed ({err}) — falling back to SQLite "
                  f"for this process. Free counters may reset until Supabase "
                  f"recovers.")
            self._fallback = _new_sqlite_store(self._fallback_path)
        self._client = None
        return self._fallback

    async def _run(self, op: str, call):
        """Retry PGRST303 (Supabase gateway clock skew), then SQLite last resort.

        Community reports: identical requests with sb_secret_ keys often succeed
        after ~300ms–1.5s. Other errors are not skew — re-raise them.
        """
        last_err: Optional[Exception] = None
        for attempt in range(3):
            try:
                return await call()
            except Exception as e:
                last_err = e
                if not _is_pgrst303(e):
                    raise
                if attempt < 2:
                    # Matches observed recoveries (~300ms / ~900ms) in
                    # supabase/supabase#49655 and community retry wrappers.
                    delay = 0.3 * (attempt + 1)
                    print(f"[WARN] Supabase {op}: PGRST303 clock skew — "
                          f"retrying in {delay:.2f}s ({attempt + 1}/2)")
                    await asyncio.sleep(delay)
                    continue
        fallback = await self._demote_to_sqlite(last_err or RuntimeError(op), op)
        return fallback

    async def get_used(self, app_user_id: str) -> int:
        fallback = await self._ensure_ready()
        if fallback is not None:
            return await fallback.get_used(app_user_id)

        async def call():
            response = await (
                self._client.table(ALLOWANCE_TABLE)
                .select("used")
                .eq("app_user_id", app_user_id)
                .limit(1)
                .execute()
            )
            rows = response.data or []
            if not rows:
                return 0
            return int(rows[0].get("used") or 0)

        result = await self._run("get_used", call)
        if isinstance(result, AllowanceStore):
            return await result.get_used(app_user_id)
        return result

    async def increment(self, app_user_id: str) -> int:
        fallback = await self._ensure_ready()
        if fallback is not None:
            return await fallback.increment(app_user_id)

        async def call():
            response = await self._client.rpc(
                INCREMENT_RPC, {"p_app_user_id": app_user_id}
            ).execute()
            data = response.data
            # The function returns a scalar integer; PostgREST may box it in a list.
            if isinstance(data, list):
                data = data[0] if data else 0
            if isinstance(data, dict):
                data = data.get(INCREMENT_RPC, data.get("used", 0))
            return int(data or 0)

        result = await self._run("increment", call)
        if isinstance(result, AllowanceStore):
            return await result.increment(app_user_id)
        return result

    async def save_onboarding_profile(self, app_user_id: str, profile: dict) -> None:
        fallback = await self._ensure_ready()
        if fallback is not None:
            return await fallback.save_onboarding_profile(app_user_id, profile)

        async def call():
            row = {"app_user_id": app_user_id}
            row.update({f: profile.get(f) for f in ONBOARDING_FIELDS})
            row["completed_at"] = _now_iso()
            await self._client.table(ONBOARDING_TABLE).upsert(row).execute()
            return None

        result = await self._run("save_onboarding_profile", call)
        if isinstance(result, AllowanceStore):
            await result.save_onboarding_profile(app_user_id, profile)

    async def close(self) -> None:
        self._client = None
        if self._fallback is not None:
            await self._fallback.close()


def _is_pgrst303(err: Exception) -> bool:
    """True for Supabase/PostgREST 'JWT issued at future' clock-skew errors."""
    code = getattr(err, "code", None)
    if code == "PGRST303":
        return True
    # supabase-py sometimes nests the payload on .json / args.
    payload = getattr(err, "json", None) or getattr(err, "args", None)
    if isinstance(payload, dict) and payload.get("code") == "PGRST303":
        return True
    text = str(err)
    return "PGRST303" in text or "JWT issued at future" in text


def _sqlite_path_from_env() -> str:
    return os.getenv("ALLOWANCE_DB_PATH", DEFAULT_SQLITE_PATH)


def _new_sqlite_store(path: str) -> SqliteAllowanceStore:
    print("[WARN] " + "=" * 66)
    print(f"[WARN] allowance store: SQLite at {path}")
    print("[WARN] On ephemeral hosting (Render free tier, Fly machines, most "
          "containers) this file is WIPED on every redeploy, which resets every "
          "user's free allowance and hands out unlimited free generations.")
    print("[WARN] Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY for production "
          "durability (see supabase_schema.sql for the table + RPC).")
    print("[WARN] " + "=" * 66)
    return SqliteAllowanceStore(path)


def get_store() -> AllowanceStore:
    """Pick a store from the environment: Supabase when configured, else SQLite."""
    url = os.getenv("SUPABASE_URL", "").strip()
    key = os.getenv("SUPABASE_SERVICE_ROLE_KEY", "").strip()
    if url and key:
        return SupabaseAllowanceStore(url, key, _sqlite_path_from_env())
    if url or key:
        print("[WARN] Supabase is only half-configured (need BOTH SUPABASE_URL "
              "and SUPABASE_SERVICE_ROLE_KEY) — using SQLite.")
    return _new_sqlite_store(_sqlite_path_from_env())
