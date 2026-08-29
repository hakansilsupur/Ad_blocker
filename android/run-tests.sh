#!/usr/bin/env bash
#
# Run the Android app's core logic on a plain JVM -- no emulator, no device.
#
# Blocklist, DnsMessage and IpPacket are written without Android imports
# precisely so this is possible: they are the parts where a mistake is silent
# (a wrong checksum, an off-by-one in a name) and hardest to see on a phone.

set -euo pipefail
cd "$(dirname "$0")"

OUT="build/test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"

javac -nowarn -d "$OUT" \
    src/io/github/hakansilsupur/adblock/Blocklist.java \
    src/io/github/hakansilsupur/adblock/DnsMessage.java \
    src/io/github/hakansilsupur/adblock/IpPacket.java \
    src/io/github/hakansilsupur/adblock/DnsCache.java \
    src/io/github/hakansilsupur/adblock/AdHints.java \
    src/io/github/hakansilsupur/adblock/CriticalDomains.java \
    tests/CoreLogicTest.java

java -cp "$OUT" CoreLogicTest
