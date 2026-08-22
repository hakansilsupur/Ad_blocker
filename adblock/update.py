"""Fetch public blocklists and compile them for both blocking engines.

One source of truth, two outputs:

* ``lists/blocklist.txt``      -- domains for the DNS sinkhole (apps + browsers)
* ``extension/rules/ads.json`` -- declarativeNetRequest rules for the extension
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

from .blocklist import Blocklist, normalize_domain

USER_AGENT = "adblock-updater/0.1 (+https://github.com/hakansilsupur/ad_blocker)"

#: Public lists, all open and distributed in hosts or Adblock Plus format.
#: They overlap heavily on purpose -- the overlap is what ranks a domain when
#: the extension cannot hold every rule.
SOURCES: dict[str, str] = {
    # The classic unified hosts file: ads plus trackers, web-leaning.
    "stevenblack": "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    # Conservative, well-maintained, few false positives.
    "hagezi-light": (
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt"
    ),
    # Mobile ad and telemetry endpoints -- the ones behind in-app ads.
    "1hosts-lite": "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/adblock.txt",
    # AdGuard's tracking servers, for the analytics side.
    "adguard-trackers": (
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master"
        "/SpywareFilter/sections/tracking_servers.txt"
    ),
}

#: MV3 guarantees 30k static rules across enabled rulesets; leave headroom for
#: the dynamic per-site allow rules the popup creates.
MAX_STATIC_RULES = 29_000


def fetch(url: str, timeout: float = 30.0) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def build(
    sources: dict[str, str] | None = None,
    *,
    seed_files: list[Path] | None = None,
    timeout: float = 30.0,
    offline: bool = False,
) -> tuple[Blocklist, Counter]:
    """Build a blocklist from ``sources`` plus any local seed files.

    Returns the list and a counter of how many sources named each domain, which
    is used to rank rules when the extension cannot hold all of them.
    """
    sources = SOURCES if sources is None else sources
    blocklist = Blocklist()
    consensus: Counter = Counter()

    #: Weight given to the hand-picked seed list, so its entries outrank any
    #: domain that merely appears in several downloaded lists.
    seed_weight = len(sources) + 1

    for path in seed_files or []:
        if not path.is_file():
            continue
        seed = Blocklist()
        seed.load_file(path)
        for domain in seed.blocked_domains:
            consensus[domain] += seed_weight
        blocklist.merge(seed)

    if offline:
        return blocklist, consensus

    for name, url in sources.items():
        try:
            text = fetch(url, timeout=timeout)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            print(f"  ! {name}: {exc}")
            continue
        # Parse each source on its own so a domain listed by several sources
        # is counted several times -- that agreement is the ranking signal.
        source_list = Blocklist()
        source_list.load_text(text, name)
        for domain in source_list.blocked_domains:
            consensus[domain] += 1
        new = len(source_list.blocked_domains - blocklist.blocked_domains)
        blocklist.merge(source_list)
        print(f"  + {name}: {len(source_list)} domains, {new} new")

    return blocklist, consensus


def build_extension_rules(
    blocklist: Blocklist,
    consensus: Counter | None = None,
    limit: int = MAX_STATIC_RULES,
) -> list[dict]:
    """Compile domains into declarativeNetRequest block rules.

    Rules deliberately omit ``resourceTypes``: the MV3 default matches every
    type *except* ``main_frame``, so subresource ads are blocked while a user
    who clicks through to an ad domain still gets a page instead of an error.
    """
    consensus = consensus or Counter()
    domains = sorted(
        blocklist.blocked_domains,
        key=lambda d: (-consensus.get(d, 0), len(d), d),
    )[:limit]

    return [
        {
            "id": index,
            "priority": 1,
            "action": {"type": "block"},
            "condition": {
                "urlFilter": f"||{domain}^",
                "isUrlFilterCaseSensitive": False,
            },
        }
        for index, domain in enumerate(sorted(domains), start=1)
    ]


def write_extension_rules(rules: list[dict], path: Path) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(rules, separators=(",", ":")) + "\n", encoding="utf-8")
    return len(rules)


def run(
    root: Path,
    *,
    offline: bool = False,
    limit: int = MAX_STATIC_RULES,
    timeout: float = 30.0,
) -> dict:
    """Refresh every generated list under ``root``. Returns a summary."""
    lists_dir = root / "lists"
    seed_files = [lists_dir / "seed.txt"]

    print("Updating blocklists...")
    blocklist, consensus = build(seed_files=seed_files, timeout=timeout, offline=offline)

    # Local exceptions must survive an update, so re-apply them before the
    # collapse pass, which needs to know where the exceptions are.
    allowlist_path = lists_dir / "allowlist.txt"
    allowed = 0
    if allowlist_path.is_file():
        for line in allowlist_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                allowed += blocklist.allow(normalize_domain(line))

    collapsed = blocklist.collapse_redundant()
    if collapsed:
        entries = "entry" if collapsed == 1 else "entries"
        print(f"  - dropped {collapsed} redundant {entries} already covered by a broader rule")

    total = blocklist.write_domains(lists_dir / "blocklist.txt")
    rules = build_extension_rules(blocklist, consensus, limit=limit)
    written = write_extension_rules(rules, root / "extension" / "rules" / "ads.json")

    summary = {
        "domains": total,
        "extension_rules": written,
        "allowlist_entries": allowed,
        "sources": len(blocklist.stats.sources),
    }
    print(
        f"\n{total} domains blocked, {written} extension rules written"
        f" ({allowed} allowlist entries applied)"
    )
    if written < total:
        print(
            f"note: {total - written} domains exceed the extension's static rule"
            " budget; the DNS sinkhole still covers them."
        )
    return summary
