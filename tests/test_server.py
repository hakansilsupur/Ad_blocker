"""End-to-end tests against a real socket, with a stub upstream resolver."""

import socket
import struct
import threading
import unittest

from adblock import dnsmsg
from adblock.blocklist import Blocklist
from adblock.cache import TTLCache
from adblock.server import AdBlockDNS, Resolver, parse_upstream

UPSTREAM_ADDRESS = bytes([93, 184, 216, 34])


class StubUpstream:
    """A UDP resolver that answers every A query with one fixed address."""

    def __init__(self, ttl: int = 300):
        self.ttl = ttl
        self.queries = 0
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.address = self.sock.getsockname()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def _serve(self):
        self.sock.settimeout(0.2)
        while not self._stop.is_set():
            try:
                query, client = self.sock.recvfrom(65535)
            except (TimeoutError, OSError):
                continue
            self.queries += 1
            try:
                question = dnsmsg.parse_question(query)
            except dnsmsg.DNSFormatError:
                continue
            answer = struct.pack(
                "!HHHIH", 0xC00C, question.qtype, question.qclass, self.ttl, 4
            ) + UPSTREAM_ADDRESS
            response = dnsmsg.build_response(query, question, answers=answer, answer_count=1)
            self.sock.sendto(response, client)

    def stop(self):
        self._stop.set()
        self._thread.join(timeout=2)
        self.sock.close()


def make_query(name: str, qtype: int = dnsmsg.TYPE_A, query_id: int = 0x4242) -> bytes:
    header = dnsmsg.HEADER.pack(query_id, dnsmsg.FLAG_RD, 1, 0, 0, 0)
    return header + dnsmsg.encode_name(name) + struct.pack("!HH", qtype, dnsmsg.CLASS_IN)


def answer_address(response: bytes) -> str | None:
    _, _, qdcount, ancount, _, _ = dnsmsg.HEADER.unpack_from(response, 0)
    if ancount == 0:
        return None
    offset = dnsmsg.HEADER_LEN
    for _ in range(qdcount):
        _, offset = dnsmsg.decode_name(response, offset)
        offset += 4
    _, offset = dnsmsg.decode_name(response, offset)
    rtype, _, _, rdlength = struct.unpack_from("!HHIH", response, offset)
    rdata = response[offset + 10 : offset + 10 + rdlength]
    if rtype == dnsmsg.TYPE_A:
        return socket.inet_ntop(socket.AF_INET, rdata)
    if rtype == dnsmsg.TYPE_AAAA:
        return socket.inet_ntop(socket.AF_INET6, rdata)
    return None


class ResolverTest(unittest.TestCase):
    def setUp(self):
        self.upstream = StubUpstream()
        self.addCleanup(self.upstream.stop)
        blocklist = Blocklist()
        blocklist.block("doubleclick.net")
        blocklist.block("ads.example.com")
        blocklist.allow("safe.doubleclick.net")
        self.resolver = Resolver(
            blocklist,
            [self.upstream.address],
            cache=TTLCache(max_entries=16),
            timeout=1.0,
        )

    def test_blocked_domain_is_sinkholed_without_touching_upstream(self):
        response = self.resolver.handle_query(make_query("ads.doubleclick.net"))
        self.assertEqual(answer_address(response), "0.0.0.0")
        self.assertEqual(self.upstream.queries, 0)
        self.assertEqual(self.resolver.stats.blocked, 1)

    def test_allowed_domain_is_forwarded(self):
        response = self.resolver.handle_query(make_query("example.com"))
        self.assertEqual(answer_address(response), "93.184.216.34")
        self.assertEqual(self.upstream.queries, 1)

    def test_allowlist_exception_reaches_upstream(self):
        response = self.resolver.handle_query(make_query("safe.doubleclick.net"))
        self.assertEqual(answer_address(response), "93.184.216.34")

    def test_second_identical_query_is_served_from_cache(self):
        self.resolver.handle_query(make_query("example.com"))
        response = self.resolver.handle_query(make_query("example.com", query_id=0x9999))
        self.assertEqual(self.upstream.queries, 1)
        self.assertEqual(self.resolver.stats.cached, 1)
        # A cached reply must carry the *new* query's transaction ID.
        self.assertEqual(dnsmsg.message_id(response), 0x9999)

    def test_blocked_aaaa_returns_unspecified_address(self):
        response = self.resolver.handle_query(
            make_query("ads.example.com", dnsmsg.TYPE_AAAA)
        )
        self.assertEqual(answer_address(response), "::")

    def test_malformed_question_gets_servfail(self):
        # A valid header claiming a question, followed by a truncated name.
        query = dnsmsg.HEADER.pack(0x1111, dnsmsg.FLAG_RD, 1, 0, 0, 0) + b"\x09trunc"
        response = self.resolver.handle_query(query)
        self.assertEqual(dnsmsg.response_rcode(response), dnsmsg.RCODE_SERVFAIL)
        self.assertEqual(dnsmsg.message_id(response), 0x1111)
        self.assertEqual(self.resolver.stats.errors, 1)

    def test_runt_packet_is_dropped(self):
        # Too short to even carry a transaction ID to reply to.
        self.assertEqual(self.resolver.handle_query(b"\x00\x01\x02"), b"")

    def test_dead_upstream_gets_servfail(self):
        # Point at a port nothing is listening on.
        self.resolver.upstreams = [("127.0.0.1", 1)]
        self.resolver.timeout = 0.3
        response = self.resolver.handle_query(make_query("example.com"))
        self.assertEqual(dnsmsg.response_rcode(response), dnsmsg.RCODE_SERVFAIL)

    def test_stats_snapshot(self):
        self.resolver.handle_query(make_query("ads.doubleclick.net"))
        self.resolver.handle_query(make_query("example.com"))
        snapshot = self.resolver.stats.snapshot()
        self.assertEqual(snapshot["queries"], 2)
        self.assertEqual(snapshot["blocked"], 1)
        self.assertEqual(snapshot["blocked_percent"], 50.0)
        self.assertIn(("ads.doubleclick.net", 1), snapshot["top_blocked"])


class ServerSocketTest(unittest.TestCase):
    """The listeners themselves: real UDP and TCP traffic on a bound port."""

    def setUp(self):
        self.upstream = StubUpstream()
        self.addCleanup(self.upstream.stop)
        blocklist = Blocklist()
        blocklist.block("doubleclick.net")
        resolver = Resolver(blocklist, [self.upstream.address], timeout=1.0)
        self.server = AdBlockDNS(resolver, host="127.0.0.1", port=0)
        self.host, self.port = self.server.start()
        self.addCleanup(self.server.stop)

    def ask_udp(self, name: str) -> bytes:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.settimeout(3)
            sock.sendto(make_query(name), (self.host, self.port))
            return sock.recv(65535)

    def ask_tcp(self, name: str) -> bytes:
        query = make_query(name)
        with socket.create_connection((self.host, self.port), timeout=3) as sock:
            sock.sendall(struct.pack("!H", len(query)) + query)
            length = struct.unpack("!H", sock.recv(2))[0]
            buffer = b""
            while len(buffer) < length:
                buffer += sock.recv(length - len(buffer))
            return buffer

    def test_udp_blocked(self):
        self.assertEqual(answer_address(self.ask_udp("ads.doubleclick.net")), "0.0.0.0")

    def test_udp_forwarded(self):
        self.assertEqual(answer_address(self.ask_udp("example.com")), "93.184.216.34")

    def test_tcp_blocked(self):
        self.assertEqual(answer_address(self.ask_tcp("ads.doubleclick.net")), "0.0.0.0")

    def test_tcp_forwarded(self):
        self.assertEqual(answer_address(self.ask_tcp("example.org")), "93.184.216.34")


class UpstreamParsingTest(unittest.TestCase):
    def test_forms(self):
        self.assertEqual(parse_upstream("1.1.1.1"), ("1.1.1.1", 53))
        self.assertEqual(parse_upstream("1.1.1.1:5353"), ("1.1.1.1", 5353))
        self.assertEqual(parse_upstream("[2606:4700:4700::1111]:53"), ("2606:4700:4700::1111", 53))


if __name__ == "__main__":
    unittest.main()
