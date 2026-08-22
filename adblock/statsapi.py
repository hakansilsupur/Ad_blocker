"""Optional JSON stats endpoint for the DNS sinkhole.

Enabled with ``adblock serve --stats-port 8053``; bound to localhost only, and
read-only -- it exposes counters, not control.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .server import Resolver


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802 -- BaseHTTPRequestHandler's naming
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        resolver: Resolver = self.server.resolver
        if path in ("/", "/stats"):
            payload = resolver.stats.snapshot()
            payload["blocklist_size"] = len(resolver.blocklist)
            payload["cache_entries"] = len(resolver.cache)
            payload["cache_hits"] = resolver.cache.hits
            payload["cache_misses"] = resolver.cache.misses
            self._respond(200, payload)
        elif path == "/health":
            self._respond(200, {"status": "ok"})
        else:
            self._respond(404, {"error": "not found"})

    def _respond(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args) -> None:  # keep the DNS log readable
        pass


def start_stats_server(resolver: Resolver, host: str = "127.0.0.1", port: int = 8053):
    """Start the stats server on a daemon thread and return it."""
    server = ThreadingHTTPServer((host, port), _Handler)
    server.resolver = resolver
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server
