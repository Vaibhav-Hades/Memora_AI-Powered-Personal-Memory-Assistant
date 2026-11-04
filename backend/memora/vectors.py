"""Brute-force cosine search over embeddings kept in SQLite.

A personal index holds thousands to tens of thousands of chunks; one matrix-vector product with numpy
searches that in milliseconds, so no separate vector database is needed.
"""

import threading

import numpy as np

from .db import Database


def to_blob(vector: list[float]) -> bytes:
    v = np.asarray(vector, dtype=np.float32)
    norm = np.linalg.norm(v)
    return (v / norm if norm else v).tobytes()


class VectorIndex:
    def __init__(self, db: Database):
        self.db = db
        self._lock = threading.Lock()
        self._version = -1
        self._ids = np.empty(0, dtype=np.int64)
        self._matrix = np.empty((0, 0), dtype=np.float32)

    def _load(self) -> None:
        """Reload the matrix from SQLite if chunks changed since the last search."""
        if self._version == self.db.version:
            return
        version = self.db.version
        rows = self.db.all_embeddings()
        # all_embeddings() already returns one model's vectors; a size mismatch inside it would be corruption, so drop it.
        if rows:
            size = len(rows[-1][1])
            rows = [r for r in rows if len(r[1]) == size]
        self._ids = np.array([cid for cid, _ in rows], dtype=np.int64)
        self._matrix = (
            np.frombuffer(b"".join(blob for _, blob in rows), dtype=np.float32).reshape(len(rows), -1)
            if rows
            else np.empty((0, 0), dtype=np.float32)
        )
        self._version = version

    def search(self, vector: list[float], limit: int) -> list[tuple[int, float]]:
        query = np.frombuffer(to_blob(vector), dtype=np.float32)
        with self._lock:
            self._load()
            if not len(self._ids) or self._matrix.shape[1] != len(query):
                return []
            scores = self._matrix @ query
            top = np.argsort(-scores)[:limit]
            return [(int(self._ids[i]), float(scores[i])) for i in top]

    def count(self) -> int:
        with self._lock:
            self._load()
            return len(self._ids)
