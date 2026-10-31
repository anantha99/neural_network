"""Mem0-style reconciliation for free-text memories.

Typed categories (climate, school, ...) reconcile by exact key. Free-text notes
have no key worth trusting, so a new note is compared against the most similar
existing notes and one of three operations is chosen: ADD a new fact, UPDATE an
existing one (invalidate it, create the successor), or NOOP (same thing again,
just add evidence). Rules decide by default; an LLM can decide when available,
and the rules remain the fallback.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from .llm.base import LLMClient
from .models import Fact
from .policy import Policy
from .retrieval import Embedder, similarity

RECONCILE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "op": {"type": "string", "enum": ["add", "update", "noop"]},
        "target_index": {"type": ["integer", "null"], "description": "index into the existing list for update/noop"},
    },
    "required": ["op", "target_index"],
    "additionalProperties": False,
}

RECONCILE_SYSTEM = (
    "You maintain a driver's memory. Given a NEW statement and a numbered list of EXISTING memories, decide: "
    "'noop' if an existing memory already says the same thing (target_index = that memory); "
    "'update' if an existing memory is about the same subject but the new statement corrects or refreshes it "
    "(target_index = that memory); otherwise 'add' (target_index = null)."
)


@dataclass
class Decision:
    op: str  # add | update | noop
    target: Optional[Fact]
    similarity: float
    by: str  # rules | llm


class Reconciler:
    def __init__(self, policy: Policy, embedder: Embedder, llm: LLMClient | None = None):
        self.policy = policy
        self.embedder = embedder
        self.llm = llm

    def decide(self, new_text: str, existing: list[Fact]) -> Decision:
        if not existing:
            return Decision("add", None, 0.0, "rules")
        scored = sorted(((similarity(new_text, str(f.value), self.embedder), f) for f in existing), key=lambda x: -x[0])
        scored = scored[: self.policy.reconcile_top_k]
        if self.llm is not None:
            try:
                listing = "\n".join(f"{i}. {f.value}" for i, (_, f) in enumerate(scored))
                out = self.llm.complete_json(RECONCILE_SYSTEM, f"NEW: {new_text}\nEXISTING:\n{listing}", RECONCILE_SCHEMA)
                op = out.get("op", "add")
                idx = out.get("target_index")
                if op in ("update", "noop") and isinstance(idx, int) and 0 <= idx < len(scored):
                    return Decision(op, scored[idx][1], scored[idx][0], "llm")
                if op == "add":
                    return Decision("add", None, scored[0][0], "llm")
            except Exception:
                pass
        best_s, best_f = scored[0]
        if best_s >= self.policy.reconcile_noop_similarity:
            return Decision("noop", best_f, best_s, "rules")
        if best_s >= self.policy.reconcile_update_similarity:
            return Decision("update", best_f, best_s, "rules")
        return Decision("add", None, best_s, "rules")
