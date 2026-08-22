"""Domain blocklist parsing and matching.

The blocklist understands the three formats that public ad-blocking lists are
actually distributed in, so a list can be dropped in without conversion:

    0.0.0.0 ads.example.com     hosts format (StevenBlack, MVPS, ...)
    ads.example.com             one domain per line
    ||ads.example.com^          Adblock Plus network rule
    @@||cdn.example.com^        Adblock Plus exception (allow)

Matching is by domain suffix: blocking ``example.com`` also blocks
``ads.example.com``. Allow rules always beat block rules, which is what makes
it possible to unbreak a site without editing the upstream list.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

# Addresses used by hosts-format lists to point a domain at nowhere. A line
# starting with one of these is a block rule; anything else is a real hosts
# entry that we must not treat as an ad domain.
_SINKHOLE_IPS = frozenset({"0.0.0.0", "127.0.0.1", "::", "::1", "0:0:0:0:0:0:0:0"})

# Never block these, even if a list says so -- doing so breaks name resolution
# itself or the machine's own connectivity.
_NEVER_BLOCK = frozenset({"localhost", "localhost.localdomain", "local", "broadcasthost"})

_DOMAIN_RE = re.compile(r"^(?!-)[a-z0-9-]{1,63}(?<!-)(\.(?!-)[a-z0-9-]{1,63}(?<!-))*$")


def normalize_domain(domain: str) -> str:
    """Lowercase a domain and strip the trailing root dot and any port."""
    domain = domain.strip().lower().rstrip(".")
    if domain.startswith("[") and "]" in domain:  # bracketed IPv6 literal
        return domain
    if domain.count(":") == 1:  # host:port, not IPv6
        domain = domain.split(":", 1)[0]
    return domain


def is_valid_domain(domain: str) -> bool:
    """True if ``domain`` looks like a hostname we could sensibly block."""
    if not domain or len(domain) > 253 or "." not in domain:
        return False
    try:
        domain.encode("ascii")
    except UnicodeEncodeError:
        return False
    return bool(_DOMAIN_RE.match(domain))


def parent_domains(domain: str) -> Iterable[str]:
    """Yield ``domain`` then each parent: a.b.c -> a.b.c, b.c, c."""
    labels = domain.split(".")
    for i in range(len(labels)):
        yield ".".join(labels[i:])


@dataclass
class ParseStats:
    """What a parse run made of its input, for reporting after an update."""

    lines: int = 0
    blocked: int = 0
    allowed: int = 0
    skipped: int = 0
    sources: list[str] = field(default_factory=list)


class Blocklist:
    """A set of blocked domains plus a set of allow (exception) domains."""

    def __init__(self) -> None:
        self._blocked: set[str] = set()
        self._allowed: set[str] = set()
        self.stats = ParseStats()

    def __len__(self) -> int:
        return len(self._blocked)

    @property
    def blocked_domains(self) -> set[str]:
        return set(self._blocked)

    @property
    def allowed_domains(self) -> set[str]:
        return set(self._allowed)

    def block(self, domain: str) -> bool:
        """Add a block rule. Returns False if the domain was unusable."""
        domain = normalize_domain(domain)
        if domain in _NEVER_BLOCK or not is_valid_domain(domain):
            return False
        self._blocked.add(domain)
        return True

    def allow(self, domain: str) -> bool:
        """Add an allow rule, which overrides any block rule."""
        domain = normalize_domain(domain)
        if not is_valid_domain(domain):
            return False
        self._allowed.add(domain)
        return True

    def is_allowed(self, domain: str) -> bool:
        """True if ``domain`` or a parent of it has an explicit allow rule."""
        domain = normalize_domain(domain)
        return any(parent in self._allowed for parent in parent_domains(domain))

    def is_blocked(self, domain: str) -> bool:
        """True if ``domain`` should be sinkholed.

        A domain is blocked when it, or any parent domain, is on the blocklist
        and no allow rule covers it.
        """
        domain = normalize_domain(domain)
        if not domain:
            return False
        for parent in parent_domains(domain):
            if parent in self._allowed:
                return False
            if parent in self._blocked:
                return True
        return False

    def merge(self, other: Blocklist) -> Blocklist:
        """Fold another list into this one."""
        self._blocked |= other._blocked
        self._allowed |= other._allowed
        self.stats.blocked += other.stats.blocked
        self.stats.allowed += other.stats.allowed
        self.stats.skipped += other.stats.skipped
        self.stats.lines += other.stats.lines
        self.stats.sources.extend(other.stats.sources)
        return self

    def collapse_redundant(self) -> int:
        """Drop entries a broader entry already covers. Returns how many.

        Blocking ``example.com`` already blocks ``ads.example.com``, and the
        public lists are full of both. Removing the child changes nothing about
        what resolves -- but it frees room in the browser extension's rule
        budget, where every entry costs a slot.

        An entry is kept if an allow rule sits between it and its blocked
        ancestor, since there the child rule is doing real work.
        """
        redundant = set()
        for domain in self._blocked:
            labels = domain.split(".")
            for i in range(1, len(labels)):
                parent = ".".join(labels[i:])
                if parent in self._allowed:
                    break  # an exception in between: the child still matters
                if parent in self._blocked:
                    redundant.add(domain)
                    break
        self._blocked -= redundant
        return len(redundant)

    def add_rule(self, line: str) -> str:
        """Apply one line of a list file.

        Returns what happened: ``"blocked"``, ``"allowed"`` or ``"skipped"``.
        """
        line = line.strip()
        if not line or line[0] in "#![":
            return "skipped"

        # Strip trailing hosts-file comments ("0.0.0.0 x.com # tracker").
        for marker in (" #", "\t#"):
            if marker in line:
                line = line.split(marker, 1)[0].strip()

        if line.startswith("@@"):
            return "allowed" if self._add_abp_rule(line[2:], self.allow) else "skipped"
        if line.startswith("||"):
            return "blocked" if self._add_abp_rule(line, self.block) else "skipped"
        # "*.example.com" means the same thing we already do for every entry.
        if line.startswith("*."):
            line = line[2:]
        # An ABP rule we do not understand (element hiding, regex, options we
        # cannot express as a domain) -- the extension handles those.
        if any(c in line for c in "|^*$/"):
            return "skipped"

        parts = line.split()
        if len(parts) >= 2:
            if parts[0] not in _SINKHOLE_IPS:
                return "skipped"
            # A hosts line may list several names for one address.
            hit = False
            for name in parts[1:]:
                hit |= self.block(name)
            return "blocked" if hit else "skipped"
        if len(parts) == 1:
            return "blocked" if self.block(parts[0]) else "skipped"
        return "skipped"

    def _add_abp_rule(self, rule: str, add) -> bool:
        """Handle ``||domain^$options``; only plain domain rules are usable."""
        if not rule.startswith("||"):
            return False
        rule = rule[2:]
        if "$" in rule:
            rule, options = rule.split("$", 1)
            # Rules scoped to a resource type or a specific site cannot be
            # expressed as a DNS decision -- blocking the domain outright
            # would over-block. Leave those to the browser extension.
            if not _abp_options_are_domain_wide(options):
                return False
        rule = rule.rstrip("^").rstrip("/")
        if not rule or any(c in rule for c in "/*^|"):
            return False
        return add(rule)

    def load_text(self, text: str, source: str = "<text>") -> ParseStats:
        """Parse a whole list, updating :attr:`stats`."""
        self.stats.sources.append(source)
        for line in text.splitlines():
            self.stats.lines += 1
            result = self.add_rule(line)
            if result == "blocked":
                self.stats.blocked += 1
            elif result == "allowed":
                self.stats.allowed += 1
            else:
                self.stats.skipped += 1
        return self.stats

    def load_file(self, path: str | Path) -> ParseStats:
        path = Path(path)
        return self.load_text(path.read_text(encoding="utf-8", errors="replace"), str(path))

    def load_files(self, paths: Iterable[str | Path]) -> ParseStats:
        for path in paths:
            p = Path(path)
            if p.is_file():
                self.load_file(p)
        return self.stats

    def write_domains(self, path: str | Path) -> int:
        """Write the blocked domains, sorted, one per line. Returns the count."""
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        domains = sorted(self._blocked)
        with path.open("w", encoding="utf-8") as fh:
            fh.write(f"# {len(domains)} blocked domains\n")
            for domain in domains:
                fh.write(domain + "\n")
        return len(domains)


def _abp_options_are_domain_wide(options: str) -> bool:
    """True if ``$options`` still means "block this domain everywhere"."""
    for option in options.split(","):
        option = option.strip().lstrip("~")
        if option in ("all", "document", "popup", "third-party", "3p", "doc"):
            continue
        return False
    return True


def load_default(list_dir: str | Path = "lists") -> Blocklist:
    """Load ``blocklist.txt`` plus ``allowlist.txt`` from ``list_dir``."""
    list_dir = Path(list_dir)
    blocklist = Blocklist()
    allowlist = list_dir / "allowlist.txt"
    blocklist.load_files(p for p in sorted(list_dir.glob("*.txt")) if p != allowlist)
    if allowlist.is_file():
        for line in allowlist.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                blocklist.allow(line.lstrip("@").lstrip("|").rstrip("^"))
    return blocklist
