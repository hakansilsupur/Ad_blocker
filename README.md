# AdBlock — for apps *and* websites

Ads reach you through several different doors, so this blocks all of them:

| Layer | What it is | What it stops |
| --- | --- | --- |
| **DNS sinkhole** (`adblock serve`) | A local resolver that answers ad and tracker hostnames with `0.0.0.0` | Ads and telemetry in **native apps**, games, smart TVs, and every browser on the device — anything that resolves a hostname |
| **Android app** (`android/`) | The same filter on the phone itself, as a local `VpnService` — no root, nothing to configure | Ads in **every app on the handset**, on mobile data as well as Wi-Fi |
| **Browser extension** (`extension/`) | Manifest V3, `declarativeNetRequest` + cosmetic filtering | Ad requests **and the empty boxes they leave behind** on websites |

Use both on the desktop. DNS is the only layer that can reach inside an app you
don't control; the extension is the only layer that can see the page and tidy up
after itself. On a phone, the Android app is both halves of the DNS story at
once: it is the resolver, and it is the thing pointing the device at it.

Standard library Python, plain JavaScript, and dependency-free Java — nothing
to install, no build step for the desktop halves, nothing phoning home.

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
IP. Both require port `53`, and it only applies on that network. On Android,
[the app](#the-android-app) is the better answer: it needs no configuration and
keeps working on mobile data. Note that Android's "Private DNS" (DoH/DoT)
bypasses a network-level resolver entirely; turn it off for that setup to work.

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

## The Android app

A local `VpnService` that filters DNS on the phone itself. No root, no DNS
settings to change, and it works on mobile data as well as Wi-Fi — which the
router approach cannot do.

The tunnel is deliberately narrow. It advertises one fake DNS server
(`10.111.222.2`) and routes **only that address**, so DNS is the only traffic
that ever enters the app. Everything else on the phone takes its normal path
and is never seen, let alone relayed:

```
   an app asks for ads.example.com
     -> Android sends the query to 10.111.222.2
     -> the kernel routes it into the tun device
     -> the service reads the question
          blocked -> answers 0.0.0.0 on the spot; nothing leaves the phone
          allowed -> relays it to your upstream over a protected socket
```

### Getting it

Grab `adblock-debug.apk` from the **Android APK** workflow run on GitHub
(Actions → latest run → Artifacts), or build it yourself:

```bash
android/build.sh                          # -> android/build/adblock-debug.apk
adb install -r android/build/adblock-debug.apk
```

`build.sh` needs a JDK, `aapt2`, `apksigner`, `zipalign`, a dexer (`d8`, or
`dalvik-exchange` on Debian/Ubuntu), and an `android.jar`. It does not need
Gradle, the Android Gradle Plugin, or a network connection. On Debian/Ubuntu:

```bash
sudo apt install android-sdk-build-tools dalvik-exchange android-sdk-platform-23
```

If you would rather use the usual toolchain, the same sources build with
Gradle — that is what Android Studio and CI use:

```bash
cd android && ./gradlew assembleDebug     # -> build/outputs/apk/debug/
```

Both paths compile the same `src/`, `res/` and `AndroidManifest.xml`; there is
no second copy of the app.

The APK is signed with a throwaway debug key, so Android will call it an
unknown app — allow installation from your file manager, or use `adb install`.
To sign with your own key: `KEYSTORE=my.jks KEY_ALIAS=upload android/build.sh`.

### Using it

Open the app, tap **Start protection**, and accept Android's VPN prompt (the
system shows this for any app that creates a tunnel; nothing leaves your phone
because of it). The screen shows what is being blocked as it happens.

The APK bundles the same curated seed list as the desktop resolver, so it
blocks from the moment it is installed. **Update blocklists** pulls the same
four public sources the desktop `adblock update` uses — around 200,000 domains
— and stores them privately in the app.

Android shows a persistent key icon while any VPN is active. That is the
system's, not the app's, and there is no way to hide it.

### When an app still shows ads

Two different problems hide behind "an ad got through", and only one of them
is fixable.

**The domain is not on the list yet.** Most common, and the app can tell you
exactly which domain it was. The **Allowed lookups** section lists every name
that resolved; open the offending app, come back, and the ad server will be
near the top. Tap it to block it. Your picks go in `userblock.txt`, separate
from the downloaded lists, so an update never discards them — and if a block
turns out to break something, `allowlist.txt` overrides it.

This beats guessing at network names from the outside: an app that mediates
through a regional network (Admost and ReklamStore in Turkey, CSJ or GDT in
Chinese-built apps) uses domains no general list is guaranteed to carry.

**The ad comes from the same domain as the content.** A sponsor banner drawn
inside the app's own layout, a rewarded video streamed from the same CDN as
everything else, YouTube's pre-roll. DNS sees one hostname serving both the
thing you want and the thing you don't, and blocking it takes the app with it.
No DNS blocker can separate these — not this one, not Pi-hole, not AdGuard's
DNS. It needs a filter that can see inside the connection.

A blocked ad slot can also leave a visible hole: a WebView that was going to
load an ad shows its own "page not available" error instead. That is the block
working. Removing the empty frame means editing the app's UI, which only an
in-app filter or a modified client can do.

### Testing it without a device

The parts that decide what happens to a packet — list matching, DNS parsing,
IP/UDP construction — are written without Android imports so they run on a
plain JVM:

```bash
android/run-tests.sh     # 83 checks, no emulator needed
```

That covers the code where a mistake is silent: a wrong checksum, an
off-by-one in a compressed name, an allow rule that fails to override.

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
- **The Android app holds the phone's one VPN slot.** Android allows a single
  active VPN, so it cannot run alongside a work VPN. It also carries UDP DNS
  only: a client that insists on DNS over TCP gets told to retry, and nothing
  answers.
- **Cosmetic filters go stale.** Sites rename their classes. A stale selector
  stops matching rather than breaking anything, but it does mean the per-site
  lists in `cosmetic-filters.js` need occasional upkeep.
- **The upstream still sees your queries.** This is a filter, not a privacy
  tunnel. Point `--upstream` at a resolver you trust.

---

## Development

```bash
python -m unittest discover -s tests -v   # 90 tests, no network needed
android/run-tests.sh                      # 83 checks on the Android logic
python -m adblock update --offline        # rebuild generated files from the seed
python tools/make_icons.py                # regenerate extension and app icons
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
android/          VpnService DNS filter for the phone (no dependencies)
  src/              Blocklist, DnsMessage, IpPacket, the service, the UI
  build.sh          APK build with SDK tools only, no Gradle
  build.gradle.kts  the same sources through the usual toolchain
  tests/            JVM tests for the packet and matching logic
lists/            seed.txt (curated), blocklist.txt (generated), allowlist.txt (yours)
tests/            unittest suite, including static checks on the extension
tools/            icon generation
```

The generated files (`lists/blocklist.txt`, `extension/rules/ads.json`) are
committed so the repo works on checkout; CI rebuilds them from the seed and
fails if they have drifted.

## License

MIT.
