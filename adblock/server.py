"""The sinkholing DNS server.

Point a device (or a whole router) at this resolver and every app on it stops
resolving ad and tracker hostnames -- which is the only way to reach ads inside
native apps, where a browser extension cannot go.
"""

from __future__ import annotations

import logging
import socket
import socketserver
import struct
import threading
import time
from collections import Counter, deque
from dataclasses import dataclass, field

from . import dnsmsg
from .blocklist import Blocklist, normalize_domain
from .cache import TTLCache

log = logging.getLogger("adblock")

DEFAULT_UPSTREAMS = ("1.1.1.1:53", "9.9.9.9:53")


def parse_upstream(value: str) -> tuple[str, int]:
    """Parse ``host`` or ``host:port`` into an address tuple."""
    value = value.strip()
    if value.startswith("["):  # [::1]:53
        host, _, rest = value[1:].partition("]")
        port = int(rest.lstrip(":") or 53)
        return host, port
    if value.count(":") == 1:
        host, _, port = value.partition(":")
        return host, int(port or 53)
    return value, 53


@dataclass
class Stats:
    """Counters and a small ring buffer, surfaced by ``adblock stats``."""

    started_at: float = field(default_factory=time.time)
    queries: int = 0
    blocked: int = 0
    cached: int = 0
    forwarded: int = 0
    errors: int = 0
    top_blocked: Counter = field(default_factory=Counter)
    recent: deque = field(default_factory=lambda: deque(maxlen=100))
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def record(self, domain: str, qtype: int, action: str) -> None:
        with self._lock:
            self.queries += 1
            if action == "blocked":
                self.blocked += 1
                self.top_blocked[domain] += 1
            elif action == "cached":
                self.cached += 1
            elif action == "forwarded":
                self.forwarded += 1
            else:
                self.errors += 1
            self.recent.append((time.time(), domain, dnsmsg.type_name(qtype), action))

    def snapshot(self) -> dict:
        with self._lock:
            blocked_pct = (self.blocked / self.queries * 100) if self.queries else 0.0
            return {
                "uptime_seconds": round(time.time() - self.started_at, 1),
                "queries": self.queries,
                "blocked": self.blocked,
                "blocked_percent": round(blocked_pct, 1),
                "cached": self.cached,
                "forwarded": self.forwarded,
                "errors": self.errors,
                "top_blocked": self.top_blocked.most_common(10),
                "recent": list(self.recent)[-20:],
            }


class Resolver:
    """Decides what happens to each query: sinkhole, serve from cache, forward."""

    def __init__(
        self,
        blocklist: Blocklist,
        upstreams: list[tuple[str, int]] | None = None,
        *,
        cache: TTLCache | None = None,
        timeout: float = 3.0,
        block_ttl: int = 60,
        nxdomain: bool = False,
        stats: Stats | None = None,
    ) -> None:
        self.blocklist = blocklist
        self.upstreams = upstreams or [parse_upstream(u) for u in DEFAULT_UPSTREAMS]
        self.cache = cache if cache is not None else TTLCache()
        self.timeout = timeout
        self.block_ttl = block_ttl
        self.nxdomain = nxdomain
        self.stats = stats or Stats()

    def handle_query(self, data: bytes) -> bytes:
        """Turn a raw query into a raw response."""
        try:
            question = dnsmsg.parse_question(data)
        except dnsmsg.DNSFormatError as exc:
            log.debug("dropping malformed query: %s", exc)
            self.stats.record("<malformed>", 0, "error")
            return dnsmsg.build_servfail(data)

        domain = normalize_domain(question.name)

        if self.blocklist.is_blocked(domain):
            self.stats.record(domain, question.qtype, "blocked")
            log.info("blocked %s (%s)", domain, dnsmsg.type_name(question.qtype))
            return dnsmsg.build_sinkhole_response(
                data, question, ttl=self.block_ttl, nxdomain=self.nxdomain
            )

        key = (domain, question.qtype, question.qclass)
        hit = self.cache.get(key)
        if hit is not None:
            self.stats.record(domain, question.qtype, "cached")
            return dnsmsg.with_id(hit, dnsmsg.message_id(data))

        response = self.forward(data)
        if response is None:
            self.stats.record(domain, question.qtype, "error")
            return dnsmsg.build_servfail(data)

        if dnsmsg.response_rcode(response) == dnsmsg.RCODE_NOERROR:
            self.cache.set(key, response, dnsmsg.response_ttl(response))
        self.stats.record(domain, question.qtype, "forwarded")
        return response

    def forward(self, query: bytes) -> bytes | None:
        """Ask each upstream in turn until one answers."""
        query_id = query[:2]
        for host, port in self.upstreams:
            family = socket.AF_INET6 if ":" in host else socket.AF_INET
            try:
                with socket.socket(family, socket.SOCK_DGRAM) as sock:
                    sock.settimeout(self.timeout)
                    sock.sendto(query, (host, port))
                    deadline = time.monotonic() + self.timeout
                    while time.monotonic() < deadline:
                        response, _ = sock.recvfrom(65535)
                        # Ignore late replies to an earlier query on this port.
                        if response[:2] == query_id:
                            return response
            except (TimeoutError, OSError) as exc:
                log.warning("upstream %s:%s failed: %s", host, port, exc)
        return None


class _UDPHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        data, sock = self.request
        response = self.server.resolver.handle_query(data)
        if response:
            sock.sendto(response, self.client_address)


class _TCPHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.request.settimeout(5.0)
        try:
            header = _recv_exactly(self.request, 2)
            if not header:
                return
            (length,) = struct.unpack("!H", header)
            query = _recv_exactly(self.request, length)
            if not query:
                return
            response = self.server.resolver.handle_query(query)
            if response:
                self.request.sendall(struct.pack("!H", len(response)) + response)
        except (TimeoutError, OSError):
            pass


def _recv_exactly(sock: socket.socket, count: int) -> bytes:
    buffer = bytearray()
    while len(buffer) < count:
        chunk = sock.recv(count - len(buffer))
        if not chunk:
            return b""
        buffer += chunk
    return bytes(buffer)


class _ThreadingUDPServer(socketserver.ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True
    max_packet_size = 65535


class _ThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class AdBlockDNS:
    """Runs the UDP and TCP listeners together."""

    def __init__(self, resolver: Resolver, host: str = "127.0.0.1", port: int = 5353) -> None:
        self.resolver = resolver
        self.host = host
        self.port = port
        self._udp: _ThreadingUDPServer | None = None
        self._tcp: _ThreadingTCPServer | None = None
        self._threads: list[threading.Thread] = []

    @property
    def address(self) -> tuple[str, int]:
        """The address actually bound (port may differ if 0 was requested)."""
        if self._udp is None:
            return self.host, self.port
        return self._udp.server_address[:2]

    def start(self) -> tuple[str, int]:
        self._udp = _ThreadingUDPServer((self.host, self.port), _UDPHandler)
        self._udp.resolver = self.resolver
        # Bind TCP to whatever port UDP actually got, so port 0 works in tests.
        bound_port = self._udp.server_address[1]
        self._tcp = _ThreadingTCPServer((self.host, bound_port), _TCPHandler)
        self._tcp.resolver = self.resolver
        for server in (self._udp, self._tcp):
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            self._threads.append(thread)
        return self.address

    def stop(self) -> None:
        for server in (self._udp, self._tcp):
            if server is not None:
                server.shutdown()
                server.server_close()
        for thread in self._threads:
            thread.join(timeout=2)
        self._udp = self._tcp = None
        self._threads.clear()

    def wait(self) -> None:
        """Block until interrupted, sweeping expired cache entries as we go."""
        try:
            while True:
                time.sleep(3600)
                self.resolver.cache.purge_expired()
        except KeyboardInterrupt:
            log.info("shutting down")
        finally:
            self.stop()

    def serve_forever(self) -> None:
        host, port = self.start()
        log.info("listening on %s:%s (udp+tcp)", host, port)
        self.wait()

    def __enter__(self) -> AdBlockDNS:
        self.start()
        return self

    def __exit__(self, *exc_info) -> None:
        self.stop()
