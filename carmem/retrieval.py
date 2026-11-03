"""Embedding and ranking. Pure Python so it runs on a head unit with no deps.

Swap :class:`HashingEmbedder` for any model that implements :class:`Embedder`.
"""

from __future__ import annotations

import hashlib
import math
import re
from typing import Iterable, Protocol, Sequence

_TOKEN = re.compile(r"[a-z0-9]+")


class Embedder(Protocol):
    dim: int

    def embed(self, text: str) -> list[float]: ...


def tokenize(text: str) -> list[str]:
    return _TOKEN.findall(text.lower())


class HashingEmbedder:
    """Feature-hashed bag of unigrams + bigrams, L2-normalised.

    Deterministic, dependency-free and good enough for small on-device stores.
    """

    def __init__(self, dim: int = 256):
        self.dim = dim

    def _slot(self, token: str) -> tuple[int, float]:
        h = hashlib.blake2b(token.encode(), digest_size=8).digest()
        idx = int.from_bytes(h[:4], "little") % self.dim
        sign = 1.0 if h[4] & 1 else -1.0
        return idx, sign

    def embed(self, text: str) -> list[float]:
        vec = [0.0] * self.dim
        toks = tokenize(text)
        feats = toks + [f"{a}_{b}" for a, b in zip(toks, toks[1:])]
        for t in feats:
            i, s = self._slot(t)
            vec[i] += s
        norm = math.sqrt(sum(v * v for v in vec)) or 1.0
        return [v / norm for v in vec]


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    return sum(x * y for x, y in zip(a, b))


def keyword_overlap(query: str, text: str) -> float:
    q = set(tokenize(query))
    if not q:
        return 0.0
    t = set(tokenize(text))
    return len(q & t) / len(q)


def score(query: str, qvec: Sequence[float], text: str, tvec: Sequence[float] | None) -> float:
    """Hybrid score: cosine on hashed embeddings plus exact keyword overlap."""
    s = keyword_overlap(query, text)
    if tvec:
        s += 0.7 * cosine(qvec, tvec)
    return s


def recency_weight(age_days: float, half_life_days: float) -> float:
    """0.5 .. 1.0 multiplier: a memory half a half-life old scores ~0.85, a very old one 0.5.

    Generative-agents style recency, bounded so relevance still dominates."""
    if half_life_days <= 0:
        return 1.0
    return 0.5 + 0.5 * (0.5 ** (max(age_days, 0.0) / half_life_days))


def similarity(a_text: str, b_text: str, embedder: "Embedder") -> float:
    """Symmetric 0..1 similarity used by the reconciler: keyword Jaccard blended with cosine."""
    ta, tb = set(tokenize(a_text)), set(tokenize(b_text))
    jacc = len(ta & tb) / len(ta | tb) if (ta | tb) else 0.0
    cos = max(0.0, cosine(embedder.embed(a_text), embedder.embed(b_text)))
    return 0.5 * jacc + 0.5 * cos


def top_k(items: Iterable[tuple[float, object]], k: int) -> list[tuple[float, object]]:
    return sorted(items, key=lambda x: x[0], reverse=True)[:k]
