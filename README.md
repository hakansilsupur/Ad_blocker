# AdBlock — for apps *and* websites

Ads reach you through two different doors, so this blocks both:

| Layer | What it is | What it stops |
| --- | --- | --- |
| **DNS sinkhole** (`adblock serve`) | A local resolver that answers ad and tracker hostnames with `0.0.0.0` | Ads and telemetry in **native apps**, games, smart TVs, and every browser on the device — anything that resolves a hostname |
| **Browser extension** (`extension/`) | Manifest V3, `declarativeNetRequest` + cosmetic filtering | Ad requests **and the empty boxes they leave behind** on websites |

Use both. DNS is the only layer that can reach inside an app you don't control;
the extension is the only layer that can see the page and tidy up after itself.

Everything is standard library Python and plain JavaScript — no dependencies,
no build step, nothing phoning home.

---

## Quick start

```bash
git clone https://github.com/hakansilsupur/ad_blocker.git
cd ad_blocker

python -m adblock update          # download public lists (or --offline for the seed list)
python -m adblock serve           # resolver on 127.0.0.1:5353
```

In a second terminal:

```bash
python -m adblock query pagead2.googlesyndication.com   # -> BLOCKED
python -m adblock query example.com                     # -> allowed
```

Then point your machine's DNS at it — see [Pointing devices at it](#pointing-devices-at-it).

### The extension

1. Open `chrome://extensions` (or `edge://extensions`, or `brave://extensions`).
2. Turn on **Developer mode**.
3. **Load unpacked** → select the `extension/` folder.

The toolbar icon shows how many requests were blocked on the current page, with
a master switch and a per-site "allow ads here" toggle for when a site breaks.

> Firefox: the manifest is MV3 and loads via `about:debugging` → *This Firefox* →
> *Load Temporary Add-on* → `extension/manifest.json`. Firefox's
> `declarativeNetRequest` support is newer than Chrome's; the network rules work,
> the `getMatchedRules` counter may read zero.

---

## Pointing devices at it

The sinkhole only sees traffic from devices configured to use it. Port `5353`
is the default so it runs without root; anything you point at it needs to name
that port, or you can move it to the standard port `53` (below).

**One Linux/macOS machine** — set DNS to `127.0.0.1` and the port to `5353`, or
run on `53` and set DNS to `127.0.0.1`.

**Everything on your network** — run it on a machine with a static IP and set
your **router's** DHCP DNS server to that IP. This is the setup that catches
phones, tablets, and smart TVs, where you cannot install anything.

```bash
# Serve on all interfaces so other devices can reach it
python -m adblock serve --host 0.0.0.0 --port 53
```

**Android / iOS** — set a static DNS server in Wi-Fi settings to the machine's
IP. Both require port `53`. Note that Android's "Private DNS" (DoH/DoT) bypasses
this entirely; turn it off for the blocker to work.

### Running on port 53

Ports below 1024 need privileges:

```bash
# Option 1: grant the capability once (Linux)
sudo setcap 'cap_net_bind_service=+ep' "$(readlink -f "$(which python3)")"

# Option 2: forward 53 to 5353 and keep the server unprivileged (Linux)
sudo iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-port 5353
sudo iptables -t nat -A PREROUTING -p tcp --dport 53 -j REDIRECT --to-port 5353

# Option 3: just run it as root
sudo python -m adblock serve --port 53
```

On systemd hosts, `packaging/adblock-dns.service` runs it as a hardened,
unprivileged service that is allowed to bind port 53. Note that Ubuntu and
Fedora ship `systemd-resolved` listening on port 53 already — disable its stub
listener first (`DNSStubListener=no` in `/etc/systemd/resolved.conf`).

---

## Living with it

```bash
python -m adblock update                  # refresh the public lists
python -m adblock check ads.example.com   # is this domain blocked, and why
python -m adblock serve --stats-port 8053 # expose JSON stats on localhost
python -m adblock stats                   # read them: queries, blocked %, top offenders
python -m adblock serve -v                # log every query as it is decided
```

**When something breaks.** A site that will not log in, or an app stuck on a
spinner, is usually one over-blocked domain. Find it with `serve -v`, then add
it to `lists/allowlist.txt`:

```
connect.facebook.net
```

The allowlist beats every block rule, including the downloaded lists, and
`update` never overwrites it. In the browser, the popup's per-site toggle does
the same thing for one website without touching the lists.

**Adding your own blocks.** Put domains in `lists/seed.txt` (one per line, hosts
format also works). `update` merges them and weights them above the public lists
when it compiles the extension's ruleset.

---

## How it works

```
                    ┌──────────────────────────┐
   any app on ─────►│  adblock serve  :5353    │
   the device       │                          │
                    │  blocked? ──► 0.0.0.0    │  ← ads never get an address
                    │  cached?  ──► reply      │
                    │  else     ──► upstream   │──► 1.1.1.1 / 9.9.9.9
                    └──────────────────────────┘

   lists/seed.txt ─┐
   public lists   ─┼─► adblock update ─┬─► lists/blocklist.txt      (DNS)
   allowlist.txt  ─┘                   └─► extension/rules/ads.json (browser)
```

**The DNS side.** `adblock/blocklist.py` parses hosts files, plain domain lists,
and the Adblock Plus rules it can express as a domain, then matches by suffix so
one entry covers every subdomain. `adblock/server.py` sinkholes matches and
forwards the rest, with a TTL+LRU cache in front. Blocked names get `0.0.0.0`
rather than `NXDOMAIN` — clients fail fast on connect instead of retrying
resolution, and apps that read `NXDOMAIN` as "the network is down" keep working.
Pass `--nxdomain` if you prefer the harder answer.

**The browser side.** `update` compiles the same domains into
`declarativeNetRequest` rules, so blocking happens in Chrome's network stack and
no JavaScript ever sees your requests. The rules deliberately omit
`resourceTypes`, which means MV3's default applies and `main_frame` is excluded:
subresource ads die, but clicking a link to an ad domain still loads a page.
`extension/src/content.js` then hides the containers the blocked requests left
behind, using the selectors in `cosmetic-filters.js`.

**Why the ruleset is smaller than the blocklist.** MV3 guarantees 30,000 static
rules and the public lists run to ~200,000 domains, so `update` does two things
before compiling. First it drops entries a broader entry already covers —
`ads.example.com` is redundant if `example.com` is blocked, and the lists are
full of both, which is worth about a quarter of them. Then it ranks what is left
by how many source lists agree on it, with your seed entries on top, and takes
as many as fit. The DNS layer has no such limit and covers the rest — one more
reason to run both.

---

## What this does not do

Being straight about the limits:

- **First-party ads survive DNS.** YouTube serves its video ads from the same
  hostnames as the video itself; no DNS blocker can separate them, and this one
  does not try. The extension's cosmetic rules hide some YouTube ad surfaces,
  not the pre-roll.
- **DoH and DoT bypass it.** A browser or app doing its own encrypted DNS never
  asks your resolver. Turn off "Secure DNS" in Chrome and "Private DNS" on
  Android, or block those endpoints at the router.
- **Hardcoded IPs bypass it.** A few ad SDKs skip DNS entirely. Blocking those
  needs a firewall rule, not a resolver.
- **Cosmetic filters go stale.** Sites rename their classes. A stale selector
  stops matching rather than breaking anything, but it does mean the per-site
  lists in `cosmetic-filters.js` need occasional upkeep.
- **The upstream still sees your queries.** This is a filter, not a privacy
  tunnel. Point `--upstream` at a resolver you trust.

---

## Development

```bash
python -m unittest discover -s tests -v   # 90 tests, no network needed
python -m adblock update --offline        # rebuild generated files from the seed
python tools/make_icons.py                # regenerate the extension icons
```

Layout:

```
adblock/          DNS resolver, blocklist engine, CLI
  blocklist.py      list parsing and suffix matching
  dnsmsg.py         DNS wire format (only what is needed)
  server.py         sinkhole + forwarder + cache
  update.py         fetch public lists, compile both outputs
extension/        Manifest V3 browser extension
  src/              service worker, content script, cosmetic filters
  rules/ads.json    generated declarativeNetRequest rules
lists/            seed.txt (curated), blocklist.txt (generated), allowlist.txt (yours)
tests/            unittest suite, including static checks on the extension
tools/            icon generation
```

The generated files (`lists/blocklist.txt`, `extension/rules/ads.json`) are
committed so the repo works on checkout; CI rebuilds them from the seed and
fails if they have drifted.

## License

MIT.
