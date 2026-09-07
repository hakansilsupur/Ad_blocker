# How this repo was built

Every line of this project came from a short conversation. This is the record
of the requests that changed the code, in order, each with what it produced.

The prompts are quoted exactly as they were written. Requests that did not
change any code — a question about the licence, and the one asking for this
file — are left out.

---

## 1. The whole thing, from an empty repository

> can you make an adblocker for the ADS bith apps and websites

The repository was empty, so this became the entire first version. "Apps and
websites" is two different problems, so it shipped as two layers: a DNS
sinkhole that answers ad hostnames with `0.0.0.0` — the only layer that can
reach inside a native app — and a Manifest V3 browser extension that blocks
requests *and* hides the empty slots they leave behind. Both compile their
rules from one source: a curated seed list merged with four public blocklists,
with redundant entries dropped and the rest ranked by how many sources agree.

`aa54272` — DNS resolver, blocklist engine, CLI, MV3 extension, 90 tests.

## 2. An Android build

> can you make a build APK for the repo

A phone cannot be pointed at a resolver running elsewhere, so this became a
`VpnService` that filters DNS on the handset itself. The tunnel is deliberately
narrow: it advertises one fake DNS server and routes only that address, so DNS
is the only traffic that ever enters the app.

Building it was the hard part. The sandbox blocks every Google host, so there
was no Android SDK and no Gradle plugin available; the APK was built from
Ubuntu's `aapt2`, `dalvik-exchange`, `zipalign` and `apksigner` driven by a
hand-written script. That is why the repo carries two build paths over one set
of sources: `android/build.sh` needs no Gradle and no network, and
`build.gradle.kts` is the conventional route for Android Studio and CI.

`92484d4` — the app, both build paths, and JVM tests for the packet logic.

## 3. A red build

> build seems failed

Both builds had actually succeeded; the failure was in the workflow step that
*verified* the result. `"$ANDROID_HOME"/build-tools/*/apksigner` expanded to
three paths on the runner, so the first ran as the command and the other two
became its arguments. `build.sh` had always picked a single version; the
workflow step, written by hand and never run locally, had not.

`6ce43a4` — select one build-tools version, assert both APKs exist.
`07368ee` — a job timeout, so a wedged runner fails promptly.

## 4. Ads still getting through

> this app what you did works generally well. but in first picture app
> (Maçkolik) there are some Tony ads, and in second pic, there is an ad video
> (Game ad)(in yi home app)

The screenshots showed the blocker working on doubleclick, so the misses were
domains the small bundled list did not carry. Rather than keep guessing at
network names from the outside, the app started showing the domains it
*allowed* through, newest first, each one tappable to block. Picks go in a
separate file so list updates never discard them.

`367766f` — allowed-lookups list with tap-to-block, Turkish translation,
Turkish and Chinese ad networks added to the seed.

## 5. A slow connection

> can this app cause making internet connection slow. i am using this app about
> 1 week without a problem but first time, yesterday i came across this slow
> internet issue. and when restart the phone issue iş gone. i am not sure this
> is related but i just wonder

Yes, it could — three defects in the relay path each produced exactly that
symptom. There was no cache at all, so every lookup crossed the network. The
thread pool queued without limit, so a slow resolver built a backlog of
lookups whose apps had already given up, which persists until the process dies
— matching "a reboot fixed it". And an unreachable first upstream cost five
seconds per query, paid again on the next one.

`1790517` — TTL+LRU cache, bounded queue that drops rather than accumulates,
2s timeout, and the resolver that last answered tried first.

## 6. A third ad, in the same slot

> yi app has this ad

Same chrome as the previous one, so one SDK served both. Finding its domain
should not require recognising names like `bigossp.com` on sight, so names
that look like ad infrastructure are now marked and coloured in the list. The
matching is per domain label, because the obvious substring version is a trap:
"download" ends in "ad".

`c1cefcd` — ad-server highlighting with tests for the near-misses, plus
interstitial networks added to the seed.

## 7. And a fourth

> still there is this ad in yi home

Three rounds of guessing was enough. The phone already held the answer, but
getting it out meant reading forty hostnames off a screen and retyping them,
so the allowed list got a Share button.

`b2df128` — share the list as plain text, ad servers marked.

## 8. The actual domains

> *(a shared list of 30 allowed lookups from the handset)*

This settled it. The seed carried the SDKs' brand names and none of them was
what the SDKs resolve: Mintegral talks to `mtgglobals.com`, Pangle to
`pangle-b.io` and `i18n-pglstatp.com`, Liftoff to `liftoff-creatives.io` —
plus `advlion.com`, `mossturbo.com` and `display.io`, which no brand list
would have suggested. All thirteen ad hosts from the capture are now blocked,
with the ten load-bearing ones verified still resolving.

The capture also exposed a hazard already shipped: `mtalk.google.com` sat two
rows below an ad server in the same tappable list, and one tap would have
stopped every notification on the phone with nothing to connect it back to
this app. Blocking a device-critical name now says what it will break first.

`395ada0` — the real serving domains, and a warning before a dangerous block.

---

## What the sequence shows

The first two rounds built the thing. Every round after that was the same
lesson arriving in different forms: **guessing which domains serve ads does not
work, and measuring does.** Three attempts at naming networks from screenshots
produced almost nothing; one shared list of real lookups fixed the app in a
single pass. Most of the later commits are not blocklist entries at all — they
are the tooling that turned "an ad got through" into a domain name someone
could act on.
