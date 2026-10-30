"""SQLite persistence. One file on the head unit is the source of truth."""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime
from typing import Any, Iterable, Optional

from .models import (
    Episode,
    Evidence,
    Fact,
    Person,
    Routine,
    State,
    iso,
    parse_iso,
)

SCHEMA = """
CREATE TABLE IF NOT EXISTS persons (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, roles TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS identity_bindings (
    kind TEXT NOT NULL, value TEXT NOT NULL, person_id TEXT NOT NULL,
    PRIMARY KEY (kind, value));
CREATE TABLE IF NOT EXISTS episodes (
    id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, kind TEXT NOT NULL,
    ts TEXT NOT NULL, text TEXT NOT NULL, payload TEXT NOT NULL, embedding TEXT);
CREATE INDEX IF NOT EXISTS episodes_scope_ts ON episodes(scope, ts);
CREATE TABLE IF NOT EXISTS facts (
    id TEXT PRIMARY KEY, scope TEXT NOT NULL, category TEXT NOT NULL, key TEXT NOT NULL,
    value TEXT NOT NULL, condition TEXT, state TEXT NOT NULL, source TEXT NOT NULL,
    sensitivity TEXT NOT NULL, support TEXT NOT NULL, contradictions TEXT NOT NULL,
    demotion INTEGER NOT NULL DEFAULT 0, consecutive_no INTEGER NOT NULL DEFAULT 0,
    created TEXT NOT NULL, updated TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS facts_scope ON facts(scope, category, key);
CREATE TABLE IF NOT EXISTS routines (
    id TEXT PRIMARY KEY, scope TEXT NOT NULL, signature TEXT NOT NULL, dow INTEGER NOT NULL,
    minute_of_day INTEGER NOT NULL, destination TEXT NOT NULL, origin TEXT NOT NULL DEFAULT '', action TEXT NOT NULL,
    state TEXT NOT NULL, support TEXT NOT NULL, contradictions TEXT NOT NULL,
    demotion INTEGER NOT NULL DEFAULT 0, consecutive_no INTEGER NOT NULL DEFAULT 0,
    first_seen TEXT NOT NULL, last_seen TEXT NOT NULL, stats TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS routines_scope ON routines(scope, dow);
CREATE TABLE IF NOT EXISTS tombstones (
    scope TEXT NOT NULL, signature TEXT NOT NULL, expires TEXT NOT NULL, reason TEXT NOT NULL,
    PRIMARY KEY (scope, signature));
CREATE TABLE IF NOT EXISTS audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, scope TEXT, event TEXT NOT NULL,
    detail TEXT NOT NULL);
"""


def _dump(v: Any) -> str:
    return json.dumps(v, sort_keys=True, default=str)


def _load(s: Optional[str]) -> Any:
    return json.loads(s) if s else None


class Store:
    def __init__(self, path: str = ":memory:"):
        self.path = path
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        self.conn.commit()

    def close(self) -> None:
        self.conn.close()

    # ---------------- persons & identity ----------------
    def add_person(self, p: Person) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO persons(id,name,roles) VALUES (?,?,?)",
            (p.id, p.name, _dump(p.roles)),
        )
        self.conn.commit()

    def get_person(self, pid: str) -> Optional[Person]:
        r = self.conn.execute("SELECT * FROM persons WHERE id=?", (pid,)).fetchone()
        return Person(r["id"], r["name"], _load(r["roles"])) if r else None

    def persons(self) -> list[Person]:
        return [Person(r["id"], r["name"], _load(r["roles"])) for r in self.conn.execute("SELECT * FROM persons")]

    def bind_identity(self, kind: str, value: str, person_id: str) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO identity_bindings(kind,value,person_id) VALUES (?,?,?)",
            (kind, value, person_id),
        )
        self.conn.commit()

    def lookup_identity(self, kind: str, value: str) -> Optional[str]:
        r = self.conn.execute(
            "SELECT person_id FROM identity_bindings WHERE kind=? AND value=?", (kind, value)
        ).fetchone()
        return r["person_id"] if r else None

    # ---------------- episodes ----------------
    def add_episode(
        self, scope: str, kind: str, ts: datetime, text: str, payload: dict[str, Any], embedding: list[float] | None
    ) -> Episode:
        cur = self.conn.execute(
            "INSERT INTO episodes(scope,kind,ts,text,payload,embedding) VALUES (?,?,?,?,?,?)",
            (scope, kind, iso(ts), text, _dump(payload), _dump(embedding) if embedding else None),
        )
        self.conn.commit()
        return Episode(cur.lastrowid, scope, kind, ts, text, payload)

    def get_episode(self, eid: int) -> Optional[Episode]:
        r = self.conn.execute("SELECT * FROM episodes WHERE id=?", (eid,)).fetchone()
        return self._episode(r) if r else None

    def episodes(
        self,
        scopes: Iterable[str],
        kinds: Iterable[str] | None = None,
        since: datetime | None = None,
        limit: int = 500,
        with_embeddings: bool = False,
    ) -> list[tuple[Episode, list[float] | None]]:
        scopes = list(scopes)
        if not scopes:
            return []
        q = f"SELECT * FROM episodes WHERE scope IN ({','.join('?' * len(scopes))})"
        args: list[Any] = list(scopes)
        if kinds:
            kinds = list(kinds)
            q += f" AND kind IN ({','.join('?' * len(kinds))})"
            args += kinds
        if since:
            q += " AND ts >= ?"
            args.append(iso(since))
        q += " ORDER BY ts DESC LIMIT ?"
        args.append(limit)
        out = []
        for r in self.conn.execute(q, args):
            out.append((self._episode(r), _load(r["embedding"]) if with_embeddings else None))
        return out

    def delete_episodes_before(self, cutoff: datetime) -> int:
        cur = self.conn.execute("DELETE FROM episodes WHERE ts < ?", (iso(cutoff),))
        self.conn.commit()
        return cur.rowcount

    @staticmethod
    def _episode(r: sqlite3.Row) -> Episode:
        return Episode(r["id"], r["scope"], r["kind"], parse_iso(r["ts"]), r["text"], _load(r["payload"]) or {})

    # ---------------- facts ----------------
    def upsert_fact(self, f: Fact) -> None:
        self.conn.execute(
            """INSERT OR REPLACE INTO facts(id,scope,category,key,value,condition,state,source,sensitivity,
               support,contradictions,demotion,consecutive_no,created,updated)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                f.id, f.scope, f.category, f.key, _dump(f.value), f.condition, f.state.value, f.source,
                f.sensitivity, _dump([e.to_dict() for e in f.support]),
                _dump([e.to_dict() for e in f.contradictions]), f.demotion, f.consecutive_no, f.created, f.updated,
            ),
        )
        self.conn.commit()

    def get_fact(self, fid: str) -> Optional[Fact]:
        r = self.conn.execute("SELECT * FROM facts WHERE id=?", (fid,)).fetchone()
        return self._fact(r) if r else None

    def facts(
        self,
        scopes: Iterable[str] | None = None,
        category: str | None = None,
        key: str | None = None,
        states: Iterable[State] | None = None,
    ) -> list[Fact]:
        q, args = "SELECT * FROM facts WHERE 1=1", []
        if scopes is not None:
            scopes = list(scopes)
            if not scopes:
                return []
            q += f" AND scope IN ({','.join('?' * len(scopes))})"
            args += scopes
        if category:
            q += " AND category=?"
            args.append(category)
        if key:
            q += " AND key=?"
            args.append(key)
        if states:
            st = [s.value for s in states]
            q += f" AND state IN ({','.join('?' * len(st))})"
            args += st
        q += " ORDER BY updated DESC"
        return [self._fact(r) for r in self.conn.execute(q, args)]

    def delete_fact(self, fid: str) -> None:
        self.conn.execute("DELETE FROM facts WHERE id=?", (fid,))
        self.conn.commit()

    @staticmethod
    def _fact(r: sqlite3.Row) -> Fact:
        return Fact(
            id=r["id"], scope=r["scope"], category=r["category"], key=r["key"], value=_load(r["value"]),
            condition=r["condition"], state=State(r["state"]), source=r["source"], sensitivity=r["sensitivity"],
            support=[Evidence.from_dict(e) for e in _load(r["support"]) or []],
            contradictions=[Evidence.from_dict(e) for e in _load(r["contradictions"]) or []],
            demotion=r["demotion"], consecutive_no=r["consecutive_no"], created=r["created"], updated=r["updated"],
        )

    # ---------------- routines ----------------
    def upsert_routine(self, rt: Routine) -> None:
        self.conn.execute(
            """INSERT OR REPLACE INTO routines(id,scope,signature,dow,minute_of_day,destination,origin,action,state,
               support,contradictions,demotion,consecutive_no,first_seen,last_seen,stats)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                rt.id, rt.scope, rt.signature, rt.dow, rt.minute_of_day, rt.destination, rt.origin, _dump(rt.action),
                rt.state.value, _dump([e.to_dict() for e in rt.support]),
                _dump([e.to_dict() for e in rt.contradictions]), rt.demotion, rt.consecutive_no,
                rt.first_seen, rt.last_seen, _dump(rt.stats),
            ),
        )
        self.conn.commit()

    def get_routine(self, rid: str) -> Optional[Routine]:
        r = self.conn.execute("SELECT * FROM routines WHERE id=?", (rid,)).fetchone()
        return self._routine(r) if r else None

    def routines(self, scopes: Iterable[str] | None = None, states: Iterable[State] | None = None) -> list[Routine]:
        q, args = "SELECT * FROM routines WHERE 1=1", []
        if scopes is not None:
            scopes = list(scopes)
            if not scopes:
                return []
            q += f" AND scope IN ({','.join('?' * len(scopes))})"
            args += scopes
        if states:
            st = [s.value for s in states]
            q += f" AND state IN ({','.join('?' * len(st))})"
            args += st
        q += " ORDER BY dow, minute_of_day"
        return [self._routine(r) for r in self.conn.execute(q, args)]

    def delete_routine(self, rid: str) -> None:
        self.conn.execute("DELETE FROM routines WHERE id=?", (rid,))
        self.conn.commit()

    @staticmethod
    def _routine(r: sqlite3.Row) -> Routine:
        return Routine(
            id=r["id"], scope=r["scope"], signature=r["signature"], dow=r["dow"], minute_of_day=r["minute_of_day"],
            destination=r["destination"], origin=r["origin"], action=_load(r["action"]) or {}, state=State(r["state"]),
            support=[Evidence.from_dict(e) for e in _load(r["support"]) or []],
            contradictions=[Evidence.from_dict(e) for e in _load(r["contradictions"]) or []],
            demotion=r["demotion"], consecutive_no=r["consecutive_no"], first_seen=r["first_seen"],
            last_seen=r["last_seen"], stats=_load(r["stats"]) or {},
        )

    # ---------------- tombstones ----------------
    def add_tombstone(self, scope: str, signature: str, expires: datetime, reason: str) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO tombstones(scope,signature,expires,reason) VALUES (?,?,?,?)",
            (scope, signature, iso(expires), reason),
        )
        self.conn.commit()

    def is_tombstoned(self, scope: str, signature: str, now: datetime) -> bool:
        r = self.conn.execute(
            "SELECT expires FROM tombstones WHERE scope=? AND signature=?", (scope, signature)
        ).fetchone()
        return bool(r) and parse_iso(r["expires"]) > now

    def tombstones(self, scope: str | None = None) -> list[dict[str, str]]:
        q, args = "SELECT * FROM tombstones", []
        if scope:
            q += " WHERE scope=?"
            args.append(scope)
        return [dict(r) for r in self.conn.execute(q, args)]

    def purge_expired_tombstones(self, now: datetime) -> int:
        cur = self.conn.execute("DELETE FROM tombstones WHERE expires <= ?", (iso(now),))
        self.conn.commit()
        return cur.rowcount

    # ---------------- scope-wide operations ----------------
    def purge_scope(self, scope: str) -> dict[str, int]:
        counts = {}
        for table in ("episodes", "facts", "routines", "tombstones"):
            counts[table] = self.conn.execute(f"DELETE FROM {table} WHERE scope=?", (scope,)).rowcount
        self.conn.commit()
        return counts

    def scopes_present(self) -> set[str]:
        out: set[str] = set()
        for table in ("episodes", "facts", "routines"):
            out |= {r[0] for r in self.conn.execute(f"SELECT DISTINCT scope FROM {table}")}
        return out

    # ---------------- audit ----------------
    def audit(self, ts: datetime, scope: str | None, event: str, detail: dict[str, Any]) -> None:
        self.conn.execute(
            "INSERT INTO audit(ts,scope,event,detail) VALUES (?,?,?,?)", (iso(ts), scope, event, _dump(detail))
        )
        self.conn.commit()

    def audit_log(self, limit: int = 100) -> list[dict[str, Any]]:
        rows = self.conn.execute("SELECT * FROM audit ORDER BY id DESC LIMIT ?", (limit,))
        return [dict(r) | {"detail": _load(r["detail"])} for r in rows]
