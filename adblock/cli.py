"""Command line entry point: ``python -m adblock <command>``."""

from __future__ import annotations

import argparse
import json
import logging
import socket
import struct
import sys
import time
from pathlib import Path

from . import __version__, dnsmsg, update
from .blocklist import Blocklist, load_default, normalize_domain
from .cache import TTLCache
from .server import DEFAULT_UPSTREAMS, AdBlockDNS, Resolver, parse_upstream

ROOT = Path(__file__).resolve().parent.parent


def _load_lists(list_dir: Path) -> Blocklist:
    blocklist = load_default(list_dir)
    if not len(blocklist):
        print(
            f"No domains loaded from {list_dir}. Run 'python -m adblock update' first.",
            file=sys.stderr,
        )
    return blocklist


def cmd_serve(args: argparse.Namespace) -> int:
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(message)s",
        datefmt="%H:%M:%S",
    )
    if not args.verbose:
        # Per-query "blocked" lines are INFO; quiet them unless asked for.
        logging.getLogger("adblock").setLevel(logging.WARNING if args.quiet else logging.INFO)

    blocklist = _load_lists(Path(args.list_dir))
    resolver = Resolver(
        blocklist,
        [parse_upstream(u) for u in args.upstream],
        cache=TTLCache(max_entries=args.cache_size),
        timeout=args.timeout,
        nxdomain=args.nxdomain,
    )
    server = AdBlockDNS(resolver, host=args.host, port=args.port)

    # Bind before announcing anything, so a failure is not preceded by a
    # cheerful "listening on ..." that never happened.
    try:
        host, port = server.start()
    except PermissionError:
        print(
            f"Cannot bind port {args.port}. Ports below 1024 need privileges; try"
            " --port 5353 and redirect 53 to it (see the README).",
            file=sys.stderr,
        )
        return 1
    except OSError as exc:
        print(f"Cannot bind {args.host}:{args.port}: {exc}", file=sys.stderr)
        return 1

    print(f"adblock {__version__}: {len(blocklist)} domains blocked")
    print(f"upstreams: {', '.join(f'{h}:{p}' for h, p in resolver.upstreams)}")
    if args.stats_port:
        from .statsapi import start_stats_server

        try:
            start_stats_server(resolver, args.host, args.stats_port)
            print(f"stats: http://{args.host}:{args.stats_port}/stats")
        except OSError as exc:
            # Losing stats is not a reason to stop resolving.
            print(f"stats endpoint unavailable on port {args.stats_port}: {exc}", file=sys.stderr)
    print(f"listening on {host}:{port} -- point your DNS settings here\n")

    server.wait()
    return 0


def cmd_update(args: argparse.Namespace) -> int:
    update.run(ROOT, offline=args.offline, limit=args.limit, timeout=args.timeout)
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    blocklist = _load_lists(Path(args.list_dir))
    exit_code = 0
    for domain in args.domains:
        domain = normalize_domain(domain)
        if blocklist.is_allowed(domain):
            print(f"ALLOWED  {domain}  (explicit exception)")
        elif blocklist.is_blocked(domain):
            print(f"BLOCKED  {domain}")
            exit_code = 1
        else:
            print(f"ok       {domain}")
    return exit_code


def cmd_query(args: argparse.Namespace) -> int:
    """Send a real DNS query to a running server -- an end-to-end smoke test."""
    host, port = parse_upstream(args.server)
    qtype = {"A": dnsmsg.TYPE_A, "AAAA": dnsmsg.TYPE_AAAA}.get(args.type.upper(), dnsmsg.TYPE_A)
    query = dnsmsg.HEADER.pack(0x1234, dnsmsg.FLAG_RD, 1, 0, 0, 0)
    query += dnsmsg.encode_name(args.domain) + struct.pack("!HH", qtype, dnsmsg.CLASS_IN)

    started = time.monotonic()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(args.timeout)
        try:
            sock.sendto(query, (host, port))
            response, _ = sock.recvfrom(65535)
        except (TimeoutError, OSError) as exc:
            print(f"no answer from {host}:{port}: {exc}", file=sys.stderr)
            return 1
    elapsed_ms = (time.monotonic() - started) * 1000

    addresses = _answer_addresses(response)
    rcode = dnsmsg.response_rcode(response)
    verdict = "BLOCKED" if _looks_sinkholed(rcode, addresses) else "allowed"
    print(f"{args.domain} ({args.type.upper()}) -> {verdict} in {elapsed_ms:.1f} ms")
    print(f"  rcode: {rcode}  answers: {', '.join(addresses) or '(none)'}")
    return 0


def _answer_addresses(response: bytes) -> list[str]:
    """Pull A/AAAA addresses out of a response, best effort."""
    try:
        _, _, qdcount, ancount, _, _ = dnsmsg.HEADER.unpack_from(response, 0)
        offset = dnsmsg.HEADER_LEN
        for _ in range(qdcount):
            _, offset = dnsmsg.decode_name(response, offset)
            offset += 4
        out = []
        for _ in range(ancount):
            _, offset = dnsmsg.decode_name(response, offset)
            rtype, _, _, rdlength = struct.unpack_from("!HHIH", response, offset)
            offset += 10
            rdata = response[offset : offset + rdlength]
            offset += rdlength
            if rtype == dnsmsg.TYPE_A and rdlength == 4:
                out.append(socket.inet_ntop(socket.AF_INET, rdata))
            elif rtype == dnsmsg.TYPE_AAAA and rdlength == 16:
                out.append(socket.inet_ntop(socket.AF_INET6, rdata))
        return out
    except (dnsmsg.DNSFormatError, struct.error, OSError):
        return []


def _looks_sinkholed(rcode: int, addresses: list[str]) -> bool:
    if rcode == dnsmsg.RCODE_NXDOMAIN:
        return True
    return bool(addresses) and all(a in ("0.0.0.0", "::") for a in addresses)


def cmd_stats(args: argparse.Namespace) -> int:
    import urllib.error
    import urllib.request

    url = f"http://{args.host}:{args.port}/stats"
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            data = json.load(response)
    except (urllib.error.URLError, OSError) as exc:
        print(f"cannot reach {url}: {exc}", file=sys.stderr)
        print("Start the server with --stats-port to enable it.", file=sys.stderr)
        return 1

    print(f"queries    {data['queries']}")
    print(f"blocked    {data['blocked']} ({data['blocked_percent']}%)")
    print(f"cached     {data['cached']}")
    print(f"forwarded  {data['forwarded']}")
    print(f"blocklist  {data['blocklist_size']} domains")
    if data["top_blocked"]:
        print("\ntop blocked:")
        for domain, count in data["top_blocked"]:
            print(f"  {count:>6}  {domain}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="adblock",
        description="Block ads for apps and websites: a DNS sinkhole plus a browser extension.",
    )
    parser.add_argument("--version", action="version", version=f"adblock {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    serve = sub.add_parser("serve", help="run the sinkholing DNS resolver")
    serve.add_argument("--host", default="127.0.0.1", help="bind address (default: %(default)s)")
    serve.add_argument("--port", type=int, default=5353, help="bind port (default: %(default)s)")
    serve.add_argument(
        "--upstream",
        action="append",
        default=None,
        metavar="HOST[:PORT]",
        help=f"upstream resolver, repeatable (default: {', '.join(DEFAULT_UPSTREAMS)})",
    )
    serve.add_argument("--list-dir", default=str(ROOT / "lists"), help="directory of list files")
    serve.add_argument("--cache-size", type=int, default=4096, help="max cached responses")
    serve.add_argument("--timeout", type=float, default=3.0, help="upstream timeout in seconds")
    serve.add_argument(
        "--nxdomain",
        action="store_true",
        help="answer blocked names with NXDOMAIN instead of 0.0.0.0",
    )
    serve.add_argument("--stats-port", type=int, default=0, help="serve JSON stats on this port")
    serve.add_argument("-v", "--verbose", action="store_true", help="log every query")
    serve.add_argument("-q", "--quiet", action="store_true", help="log warnings only")
    serve.set_defaults(func=cmd_serve)

    upd = sub.add_parser("update", help="download blocklists and rebuild both engines' lists")
    upd.add_argument("--offline", action="store_true", help="rebuild from the seed list only")
    upd.add_argument(
        "--limit",
        type=int,
        default=update.MAX_STATIC_RULES,
        help="max extension rules (default: %(default)s)",
    )
    upd.add_argument("--timeout", type=float, default=30.0, help="download timeout in seconds")
    upd.set_defaults(func=cmd_update)

    check = sub.add_parser("check", help="test domains against the loaded lists")
    check.add_argument("domains", nargs="+")
    check.add_argument("--list-dir", default=str(ROOT / "lists"))
    check.set_defaults(func=cmd_check)

    query = sub.add_parser("query", help="send a live DNS query to a running server")
    query.add_argument("domain")
    query.add_argument("--server", default="127.0.0.1:5353", help="resolver to ask")
    query.add_argument("--type", default="A", choices=["A", "a", "AAAA", "aaaa"])
    query.add_argument("--timeout", type=float, default=3.0)
    query.set_defaults(func=cmd_query)

    stats = sub.add_parser("stats", help="print stats from a running server")
    stats.add_argument("--host", default="127.0.0.1")
    stats.add_argument("--port", type=int, default=8053)
    stats.set_defaults(func=cmd_stats)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if getattr(args, "upstream", None) is None and args.command == "serve":
        args.upstream = list(DEFAULT_UPSTREAMS)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
