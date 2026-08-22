"""A small TTL + LRU cache for upstream DNS responses."""

from __future__ import annotations

import threading
import time
from collections import OrderedDict
from collections.abc import Hashable


class TTLCache:
    """Thread-safe cache keyed by ``(name, qtype, qclass)``.

    Entries expire on their DNS TTL and the cache evicts least-recently-used
    entries once it is full, so memory stays bounded on a busy network.
    """

    def __init__(self, max_entries: int = 4096, clock=time.monotonic) -> None:
        self.max_entries = max_entries
        self._clock = clock
        self._entries: OrderedDict[Hashable, tuple[float, bytes]] = OrderedDict()
        self._lock = threading.Lock()
        self.hits = 0
        self.misses = 0

    def __len__(self) -> int:
        with self._lock:
            return len(self._entries)

    def get(self, key: Hashable) -> bytes | None:
        now = self._clock()
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                self.misses += 1
                return None
            expires_at, payload = entry
            if expires_at <= now:
                del self._entries[key]
                self.misses += 1
                return None
            self._entries.move_to_end(key)
            self.hits += 1
            return payload

    def set(self, key: Hashable, payload: bytes, ttl: float) -> None:
        if ttl <= 0 or self.max_entries <= 0:
            return
        with self._lock:
            self._entries[key] = (self._clock() + ttl, payload)
            self._entries.move_to_end(key)
            while len(self._entries) > self.max_entries:
                self._entries.popitem(last=False)

    def purge_expired(self) -> int:
        """Drop expired entries; returns how many were removed."""
        now = self._clock()
        with self._lock:
            stale = [key for key, (expires_at, _) in self._entries.items() if expires_at <= now]
            for key in stale:
                del self._entries[key]
        return len(stale)

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()
