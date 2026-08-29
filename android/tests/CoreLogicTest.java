import io.github.hakansilsupur.adblock.AdHints;
import io.github.hakansilsupur.adblock.Blocklist;
import io.github.hakansilsupur.adblock.CriticalDomains;
import io.github.hakansilsupur.adblock.DnsCache;
import io.github.hakansilsupur.adblock.DnsMessage;
import io.github.hakansilsupur.adblock.IpPacket;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the parts of the Android app that decide what gets blocked and
 * what the phone gets told.
 *
 * <p>{@code Blocklist}, {@code DnsMessage} and {@code IpPacket} deliberately
 * touch no Android APIs, so they can run on a plain JVM. That matters: packet
 * checksums and wire-format parsing fail silently and are miserable to debug
 * on a handset, and this is the layer a device test could not reach anyway.
 *
 * <p>Run with {@code android/run-tests.sh}. No test framework, so the whole
 * thing stays dependency-free like the rest of the build.
 */
public final class CoreLogicTest {

    private static final List<String> failures = new ArrayList<String>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        blocklistMatching();
        blocklistParsing();
        blocklistNeverBlocksLocalhost();
        dnsQuestionParsing();
        dnsSinkholeAnswers();
        dnsRejectsMalformed();
        ipPacketInspection();
        ipReplyChecksums();
        endToEndBlockedLookup();
        dnsTtlParsing();
        cacheBehaviour();
        cachedAnswerMatchesTheAsker();
        adHints();
        criticalDomains();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("OK - " + checks + " checks passed");
            return;
        }
        System.out.println(failures.size() + " of " + checks + " checks FAILED:");
        for (int i = 0; i < failures.size(); i++) {
            System.out.println("  - " + failures.get(i));
        }
        System.exit(1);
    }

    // ------------------------------------------------------------- assertions

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            failures.add(what);
        }
    }

    private static void equal(int actual, int expected, String what) {
        check(actual == expected, what + " (expected " + expected + ", got " + actual + ")");
    }

    private static void equal(String actual, String expected, String what) {
        check(expected.equals(actual), what + " (expected " + expected + ", got " + actual + ")");
    }

    private static void section(String name) {
        System.out.println("- " + name);
    }

    // -------------------------------------------------------------- blocklist

    private static void blocklistMatching() {
        section("blocklist matching");
        Blocklist list = new Blocklist();
        list.block("doubleclick.net");

        check(list.isBlocked("doubleclick.net"), "exact domain is blocked");
        check(list.isBlocked("stats.g.doubleclick.net"), "subdomain is blocked");
        check(list.isBlocked("STATS.DoubleClick.NET."), "matching ignores case and trailing dot");
        check(!list.isBlocked("example.com"), "unrelated domain is not blocked");
        check(!list.isBlocked("notdoubleclick.net"), "suffix lookalike is not blocked");

        list.allow("safe.doubleclick.net");
        check(!list.isBlocked("safe.doubleclick.net"), "allow rule beats block rule");
        check(!list.isBlocked("deep.safe.doubleclick.net"), "allow rule covers subdomains");
        check(list.isBlocked("ads.doubleclick.net"), "allow rule is not over-broad");

        // The nearest rule to the queried name wins.
        Blocklist nested = new Blocklist();
        nested.allow("example.com");
        nested.block("ads.example.com");
        check(nested.isBlocked("ads.example.com"), "nearer block beats broader allow");
        check(!nested.isBlocked("other.example.com"), "broader allow still applies elsewhere");
    }

    private static void blocklistParsing() throws IOException {
        section("blocklist parsing");
        Blocklist list = parse(
                "# a comment\n"
                + "! another comment\n"
                + "[Adblock Plus 2.0]\n"
                + "0.0.0.0 ads.example.com\n"
                + "127.0.0.1 track.example.com # trailing comment\n"
                + "0.0.0.0 multi-a.example.com multi-b.example.com\n"
                + "192.168.1.10 nas.example.com\n"
                + "plain.example.com\n"
                + "*.wild.example.com\n"
                + "||abp.example.com^\n"
                + "||scoped.example.com^$image\n"
                + "||wide.example.com^$third-party\n"
                + "@@||exception.example.com^\n"
                + "example.com##.ad-slot\n");

        check(list.isBlocked("ads.example.com"), "hosts format");
        check(list.isBlocked("track.example.com"), "hosts format with trailing comment");
        check(list.isBlocked("multi-a.example.com"), "several names on one hosts line (first)");
        check(list.isBlocked("multi-b.example.com"), "several names on one hosts line (second)");
        check(!list.isBlocked("nas.example.com"), "a real hosts entry is not an ad rule");
        check(list.isBlocked("plain.example.com"), "plain domain line");
        check(list.isBlocked("sub.wild.example.com"), "wildcard prefix");
        check(list.isBlocked("abp.example.com"), "Adblock Plus network rule");
        check(!list.isBlocked("scoped.example.com"), "type-scoped ABP rule is skipped");
        check(list.isBlocked("wide.example.com"), "domain-wide ABP option is honoured");

        Blocklist withException = parse("||exception.example.com^\n@@||exception.example.com^\n");
        check(!withException.isBlocked("exception.example.com"), "ABP exception rule");

        check(!list.isBlocked("example.com"), "cosmetic rule does not block the site");
    }

    private static void blocklistNeverBlocksLocalhost() {
        section("blocklist safety");
        Blocklist list = new Blocklist();
        check(!list.block("localhost"), "localhost is refused");
        check(!list.isBlocked("localhost"), "localhost stays resolvable");
        check(!list.block("no-dot"), "a name without a dot is refused");
        check(!list.block("bad_underscore.example.com"), "an invalid character is refused");
    }

    private static Blocklist parse(String text) throws IOException {
        Blocklist list = new Blocklist();
        InputStream in = new ByteArrayInputStream(text.getBytes("UTF-8"));
        list.load(in);
        in.close();
        return list;
    }

    // ------------------------------------------------------------------- DNS

    private static void dnsQuestionParsing() {
        section("DNS question parsing");
        byte[] query = dnsQuery(0x1234, "ads.example.com", DnsMessage.TYPE_A);
        DnsMessage.Question question = DnsMessage.parseQuestion(query, 0, query.length);

        check(question != null, "a well-formed query parses");
        if (question == null) {
            return;
        }
        equal(question.name, "ads.example.com", "question name");
        equal(question.type, DnsMessage.TYPE_A, "question type");
        equal(question.dnsClass, DnsMessage.CLASS_IN, "question class");
        equal(question.end, query.length, "question ends where the message does");

        // Offsets matter: in the app the payload sits inside a larger packet.
        byte[] embedded = new byte[query.length + 28];
        System.arraycopy(query, 0, embedded, 28, query.length);
        DnsMessage.Question offsetQuestion =
                DnsMessage.parseQuestion(embedded, 28, query.length);
        check(offsetQuestion != null && "ads.example.com".equals(offsetQuestion.name),
                "parsing respects the payload offset");
    }

    private static void dnsSinkholeAnswers() {
        section("DNS sinkhole answers");

        byte[] query = dnsQuery(0xBEEF, "ads.example.com", DnsMessage.TYPE_A);
        DnsMessage.Question question = DnsMessage.parseQuestion(query, 0, query.length);
        byte[] answer = DnsMessage.buildSinkholeResponse(query, 0, query.length, question, 60);

        equal(u16(answer, 0), 0xBEEF, "transaction id is echoed");
        check((u16(answer, 2) & 0x8000) != 0, "response bit is set");
        equal(u16(answer, 2) & 0x000F, 0, "rcode is NOERROR");
        equal(u16(answer, 4), 1, "one question is echoed");
        equal(u16(answer, 6), 1, "one answer record");
        // Record layout: name pointer, type, class, ttl, rdlength, rdata.
        int record = answer.length - 16;
        equal(u16(answer, record), 0xC00C, "answer points back at the question name");
        equal(u16(answer, record + 2), DnsMessage.TYPE_A, "answer type is A");
        equal(u16(answer, record + 10), 4, "A record data is four bytes");
        check(answer[answer.length - 4] == 0 && answer[answer.length - 3] == 0
                        && answer[answer.length - 2] == 0 && answer[answer.length - 1] == 0,
                "A record answers 0.0.0.0");

        byte[] v6Query = dnsQuery(1, "ads.example.com", DnsMessage.TYPE_AAAA);
        DnsMessage.Question v6 = DnsMessage.parseQuestion(v6Query, 0, v6Query.length);
        byte[] v6Answer =
                DnsMessage.buildSinkholeResponse(v6Query, 0, v6Query.length, v6, 60);
        equal(u16(v6Answer, 6), 1, "AAAA gets an answer record");
        equal(u16(v6Answer, v6Answer.length - 18), 16, "AAAA record data is sixteen bytes");
        boolean allZero = true;
        for (int i = v6Answer.length - 16; i < v6Answer.length; i++) {
            allZero &= v6Answer[i] == 0;
        }
        check(allZero, "AAAA record answers ::");

        byte[] txtQuery = dnsQuery(2, "ads.example.com", 16);
        DnsMessage.Question txt = DnsMessage.parseQuestion(txtQuery, 0, txtQuery.length);
        byte[] txtAnswer =
                DnsMessage.buildSinkholeResponse(txtQuery, 0, txtQuery.length, txt, 60);
        equal(u16(txtAnswer, 6), 0, "other types get an empty NOERROR");
        equal(u16(txtAnswer, 2) & 0x000F, 0, "empty answer is still NOERROR");

        byte[] truncated = DnsMessage.buildTruncatedResponse(query, 0, question);
        check((u16(truncated, 2) & 0x0200) != 0, "truncated reply sets the TC bit");
        equal(u16(truncated, 6), 0, "truncated reply carries no answers");
    }

    private static void dnsRejectsMalformed() {
        section("DNS rejects malformed input");
        check(DnsMessage.parseQuestion(new byte[] {0, 1, 2}, 0, 3) == null,
                "a runt message is rejected");

        // A label length that runs past the end of the buffer.
        byte[] truncated = new byte[] {
            0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, (byte) 9, 't', 'r', 'u', 'n', 'c',
        };
        check(DnsMessage.parseQuestion(truncated, 0, truncated.length) == null,
                "a truncated name is rejected");

        // A compression pointer that points at itself would otherwise spin.
        byte[] loop = new byte[] {
            0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, (byte) 0xC0, (byte) 0x0C, 0, 1, 0, 1,
        };
        check(DnsMessage.parseQuestion(loop, 0, loop.length) == null,
                "a self-referential pointer is rejected");

        byte[] response = dnsQuery(1, "example.com", DnsMessage.TYPE_A);
        response[2] = (byte) 0x80; // set QR
        check(DnsMessage.parseQuestion(response, 0, response.length) == null,
                "a response is not treated as a query");
    }

    // ------------------------------------------------------------- IP packets

    private static void ipPacketInspection() {
        section("IP packet inspection");
        byte[] payload = dnsQuery(1, "ads.example.com", DnsMessage.TYPE_A);
        byte[] packet = udpPacket("192.168.1.50", "10.111.222.2", 45000, 53, payload);

        check(IpPacket.isIpv4(packet, packet.length), "recognises IPv4");
        check(IpPacket.isUdp(packet, packet.length), "recognises UDP");
        check(!IpPacket.isFragment(packet), "an unfragmented packet is not a fragment");
        equal(IpPacket.sourcePort(packet), 45000, "source port");
        equal(IpPacket.destinationPort(packet), 53, "destination port");
        equal(IpPacket.payloadLength(packet, packet.length), payload.length, "payload length");
        equal(IpPacket.payloadOffset(packet), 28, "payload offset past both headers");

        // A fragment carries only part of the datagram and must be skipped.
        byte[] fragment = udpPacket("192.168.1.50", "10.111.222.2", 45000, 53, payload);
        fragment[6] = 0x20; // more-fragments flag
        check(IpPacket.isFragment(fragment), "recognises a fragment");

        byte[] tcp = udpPacket("192.168.1.50", "10.111.222.2", 45000, 53, payload);
        tcp[9] = 6; // TCP
        check(!IpPacket.isUdp(tcp, tcp.length), "a TCP packet is not treated as UDP");
    }

    private static void ipReplyChecksums() {
        section("IP reply construction");
        byte[] payload = dnsQuery(1, "ads.example.com", DnsMessage.TYPE_A);
        byte[] request = udpPacket("192.168.1.50", "10.111.222.2", 45000, 53, payload);
        byte[] answer = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] reply = IpPacket.buildUdpReply(request, answer, 77);

        equal(reply.length, 20 + 8 + answer.length, "reply length");
        equal(u16(reply, 2), reply.length, "total length field");
        equal(reply[9] & 0xFF, 17, "protocol is UDP");
        equal(reply[8] & 0xFF, 64, "TTL is set");

        // Addresses and ports are swapped: the reply comes from the resolver.
        check(sameBytes(reply, 12, request, 16, 4), "reply source is the request destination");
        check(sameBytes(reply, 16, request, 12, 4), "reply destination is the request source");
        equal(u16(reply, 20), 53, "reply comes from port 53");
        equal(u16(reply, 22), 45000, "reply goes to the client's port");
        equal(u16(reply, 24), 8 + answer.length, "UDP length field");

        // A correct checksum makes the whole header sum to zero.
        equal(onesComplementSum(reply, 0, 20), 0, "IPv4 header checksum verifies");
        equal(udpChecksumOf(reply), 0, "UDP checksum verifies");
        check(u16(reply, 26) != 0, "UDP checksum field is populated");
    }

    // --------------------------------------------------------------- together

    /** The exact path a blocked lookup takes inside the service. */
    private static void endToEndBlockedLookup() {
        section("end to end: a blocked lookup");
        Blocklist list = new Blocklist();
        list.block("doubleclick.net");

        byte[] payload = dnsQuery(0x4242, "stats.g.doubleclick.net", DnsMessage.TYPE_A);
        byte[] request = udpPacket("192.168.1.50", "10.111.222.2", 51000, 53, payload);

        check(IpPacket.isUdp(request, request.length)
                        && IpPacket.destinationPort(request) == 53,
                "the service would accept this packet");

        int offset = IpPacket.payloadOffset(request);
        int length = IpPacket.payloadLength(request, request.length);
        DnsMessage.Question question = DnsMessage.parseQuestion(request, offset, length);
        check(question != null, "the question parses out of the packet");
        if (question == null) {
            return;
        }
        equal(question.name, "stats.g.doubleclick.net", "the name comes through intact");
        check(list.isBlocked(question.name), "the name is recognised as blocked");

        byte[] answer =
                DnsMessage.buildSinkholeResponse(request, offset, length, question, 60);
        byte[] reply = IpPacket.buildUdpReply(request, answer, 1);

        equal(onesComplementSum(reply, 0, 20), 0, "the reply's IP checksum verifies");
        equal(udpChecksumOf(reply), 0, "the reply's UDP checksum verifies");
        equal(u16(reply, 28), 0x4242, "the DNS transaction id survives the round trip");
        equal(u16(reply, 34), 1, "the reply answers the question");
        check(reply[reply.length - 4] == 0 && reply[reply.length - 1] == 0,
                "the phone is told 0.0.0.0");

        // And an allowed name must not be answered locally.
        byte[] allowedPayload = dnsQuery(1, "example.com", DnsMessage.TYPE_A);
        DnsMessage.Question allowed =
                DnsMessage.parseQuestion(allowedPayload, 0, allowedPayload.length);
        check(!list.isBlocked(allowed.name), "an unlisted name is forwarded instead");
    }

    // ------------------------------------------------------------- ad hints

    private static void adHints() {
        section("ad hints");
        String[] adLike = {
            "ads.example.com", "adserver.example.com", "track.example.com",
            "analytics.example.com", "pubads.g.doubleclick.net", "ads4.example.net",
            "bigossp.com", "sdk.admost.com", "static.applovin.com",
            "auction.unityads.unity3d.com", "csjplatform.com", "rtb.example.com",
            "telemetry.example.com", "reklamstore.com",
        };
        for (int i = 0; i < adLike.length; i++) {
            check(AdHints.looksLikeAd(adLike[i]), "flags " + adLike[i]);
        }

        // False positives are what would make the highlight useless, so the
        // near-misses matter more than the hits.
        String[] ordinary = {
            "download.example.com",      // ends in "ad"
            "broadcast.example.com",     // contains "ad"
            "loadbalancer.example.com",  // contains "ad"
            "upload.example.com",
            "api.github.com",
            "www.google.com",
            "cdn.jsdelivr.net",
            "mail.example.com",
            "static.wikipedia.org",
            "adana.example.com",         // a Turkish city, not an ad server
        };
        for (int i = 0; i < ordinary.length; i++) {
            check(!AdHints.looksLikeAd(ordinary[i]), "does not flag " + ordinary[i]);
        }

        check(!AdHints.looksLikeAd(null), "handles null");
        check(!AdHints.looksLikeAd(""), "handles empty");
    }

    // ------------------------------------------------------ critical domains

    private static void criticalDomains() {
        section("critical domains");
        // Straight from a real capture: this sat two rows below an ad server
        // in the lookups list, and one tap would have killed every
        // notification on the phone.
        check(CriticalDomains.isCritical("mtalk.google.com"), "push endpoint is protected");
        check(CriticalDomains.isCritical("connectivitycheck.gstatic.com"),
                "connectivity check is protected");
        check(CriticalDomains.isCritical("firebaseinstallations.googleapis.com"),
                "subdomains of a protected name are protected");
        check(CriticalDomains.isCritical("android.clients.google.com"),
                "Play services endpoint is protected");

        equal(CriticalDomains.whatBreaks("mtalk.google.com"), "push notifications",
                "says what breaks");
        check(CriticalDomains.whatBreaks("mtgglobals.com") == null,
                "an ad server carries no warning");
        check(CriticalDomains.whatBreaks("mraid.bigo.sg") == null,
                "an ad subdomain carries no warning");
        check(CriticalDomains.whatBreaks(null) == null, "handles null");

        // The warning must not fire on a lookalike suffix.
        check(!CriticalDomains.isCritical("notgoogleapis.com"),
                "a suffix lookalike is not protected");
    }

    // ----------------------------------------------------------------- cache

    private static void dnsTtlParsing() {
        section("DNS TTL parsing");
        byte[] response = dnsResponse(1, "example.com", 300);
        equal(DnsMessage.minTtlSeconds(response, 0, response.length, -1), 300,
                "reads the TTL from an answer");
        equal(DnsMessage.rcode(response, 0, response.length), 0, "reads the rcode");

        // A question-only message has no records to take a TTL from.
        byte[] query = dnsQuery(1, "example.com", DnsMessage.TYPE_A);
        equal(DnsMessage.minTtlSeconds(query, 0, query.length, 42), 42,
                "falls back when there are no records");

        // Claims an answer but stops short: must not read past the buffer.
        byte[] truncated = new byte[] {
            0, 1, (byte) 0x81, (byte) 0x80, 0, 0, 0, 1, 0, 0, 0, 0, (byte) 9, 'x',
        };
        equal(DnsMessage.minTtlSeconds(truncated, 0, truncated.length, 7), 7,
                "falls back on a truncated record");
    }

    private static void cacheBehaviour() {
        section("cache behaviour");
        DnsCache cache = new DnsCache(3);
        long now = 1000000L;
        String key = DnsCache.key("example.com", DnsMessage.TYPE_A, DnsMessage.CLASS_IN);

        check(cache.get(key, now) == null, "an empty cache misses");
        cache.put(key, new byte[] {1, 2, 3}, 60, now);
        check(cache.get(key, now) != null, "a stored answer is found");

        // The floor and ceiling are what stop a one-second TTL from being
        // useless and a one-day TTL from outliving a change of network.
        equal(cache.get(key, now + 59_000) != null ? 1 : 0, 1, "still valid before its TTL");
        check(cache.get(key, now + 61_000) == null, "expired after its TTL");

        DnsCache floors = new DnsCache(3);
        floors.put(key, new byte[] {1}, 1, now);
        check(floors.get(key, now + 20_000) != null, "a tiny TTL is raised to the floor");

        DnsCache ceiling = new DnsCache(3);
        ceiling.put(key, new byte[] {1}, 86400, now);
        check(ceiling.get(key, now + (DnsCache.MAX_TTL_SECONDS + 5) * 1000L) == null,
                "a huge TTL is capped");

        // Keys must separate record types, or an A lookup could be answered
        // with a AAAA record.
        String v6 = DnsCache.key("example.com", DnsMessage.TYPE_AAAA, DnsMessage.CLASS_IN);
        check(!key.equals(v6), "A and AAAA use different keys");
        check(cache.get(v6, now) == null, "a AAAA lookup does not hit the A entry");

        DnsCache small = new DnsCache(2);
        small.put("a", new byte[] {1}, 60, now);
        small.put("b", new byte[] {2}, 60, now);
        small.get("a", now);                       // "b" is now least recently used
        small.put("c", new byte[] {3}, 60, now);
        equal(small.size(), 2, "capacity is respected");
        check(small.get("a", now) != null, "the recently used entry survives");
        check(small.get("b", now) == null, "the least recently used entry is evicted");

        cache.clear();
        equal(cache.size(), 0, "clearing empties the cache");
    }

    /** A cached reply must carry the new asker's transaction ID, not the old one. */
    private static void cachedAnswerMatchesTheAsker() {
        section("cached answers match the asker");
        byte[] stored = dnsResponse(0x1111, "example.com", 300);
        byte[] newQuery = dnsQuery(0x2222, "example.com", DnsMessage.TYPE_A);

        byte[] reply = new byte[stored.length];
        System.arraycopy(stored, 0, reply, 0, stored.length);
        DnsMessage.setId(reply, newQuery, 0);

        equal(u16(reply, 0), 0x2222, "the reply takes the new query's id");
        check(sameBytes(reply, 2, stored, 2, stored.length - 2),
                "nothing but the id changes");
    }

    // ---------------------------------------------------------------- helpers

    /** Build a response with one A record, as an upstream would return. */
    private static byte[] dnsResponse(int id, String name, int ttl) {
        byte[] query = dnsQuery(id, name, DnsMessage.TYPE_A);
        byte[] out = new byte[query.length + 16];
        System.arraycopy(query, 0, out, 0, query.length);
        out[2] = (byte) 0x81;   // response, recursion desired
        out[3] = (byte) 0x80;   // recursion available, NOERROR
        out[7] = 1;             // one answer
        int at = query.length;
        out[at] = (byte) 0xC0;  // pointer to the question's name
        out[at + 1] = 0x0C;
        out[at + 3] = (byte) DnsMessage.TYPE_A;
        out[at + 5] = (byte) DnsMessage.CLASS_IN;
        out[at + 6] = (byte) ((ttl >> 24) & 0xFF);
        out[at + 7] = (byte) ((ttl >> 16) & 0xFF);
        out[at + 8] = (byte) ((ttl >> 8) & 0xFF);
        out[at + 9] = (byte) (ttl & 0xFF);
        out[at + 11] = 4;       // rdlength
        out[at + 12] = 93;
        out[at + 13] = (byte) 184;
        out[at + 14] = (byte) 216;
        out[at + 15] = 34;
        return out;
    }

    /** Build a DNS query the way a phone's resolver would. */
    private static byte[] dnsQuery(int id, String name, int type) {
        String[] labels = name.split("\\.");
        int nameBytes = 1;
        for (int i = 0; i < labels.length; i++) {
            nameBytes += 1 + labels[i].length();
        }
        byte[] query = new byte[12 + nameBytes + 4];
        query[0] = (byte) ((id >> 8) & 0xFF);
        query[1] = (byte) (id & 0xFF);
        query[2] = 0x01; // recursion desired
        query[5] = 0x01; // one question
        int at = 12;
        for (int i = 0; i < labels.length; i++) {
            query[at++] = (byte) labels[i].length();
            for (int c = 0; c < labels[i].length(); c++) {
                query[at++] = (byte) labels[i].charAt(c);
            }
        }
        query[at++] = 0;
        query[at++] = (byte) ((type >> 8) & 0xFF);
        query[at++] = (byte) (type & 0xFF);
        query[at++] = 0;
        query[at] = 1; // IN
        return query;
    }

    /** Wrap a payload in IPv4 + UDP headers, as the tun device would deliver it. */
    private static byte[] udpPacket(
            String source, String destination, int sourcePort, int destPort, byte[] payload) {
        byte[] packet = new byte[20 + 8 + payload.length];
        packet[0] = 0x45;
        packet[2] = (byte) ((packet.length >> 8) & 0xFF);
        packet[3] = (byte) (packet.length & 0xFF);
        packet[6] = 0x40; // don't fragment
        packet[8] = 64;
        packet[9] = 17;
        writeAddress(packet, 12, source);
        writeAddress(packet, 16, destination);
        packet[20] = (byte) ((sourcePort >> 8) & 0xFF);
        packet[21] = (byte) (sourcePort & 0xFF);
        packet[22] = (byte) ((destPort >> 8) & 0xFF);
        packet[23] = (byte) (destPort & 0xFF);
        int udpLength = 8 + payload.length;
        packet[24] = (byte) ((udpLength >> 8) & 0xFF);
        packet[25] = (byte) (udpLength & 0xFF);
        System.arraycopy(payload, 0, packet, 28, payload.length);
        return packet;
    }

    private static void writeAddress(byte[] packet, int offset, String address) {
        String[] parts = address.split("\\.");
        for (int i = 0; i < 4; i++) {
            packet[offset + i] = (byte) Integer.parseInt(parts[i]);
        }
    }

    private static int u16(byte[] data, int index) {
        return ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
    }

    private static boolean sameBytes(byte[] a, int aAt, byte[] b, int bAt, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aAt + i] != b[bAt + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sum a range the way a receiver checks it. A range that already carries a
     * correct checksum sums to zero.
     */
    private static int onesComplementSum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length - 1; i += 2) {
            sum += u16(data, offset + i);
        }
        if ((length & 1) != 0) {
            sum += (data[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    /** Verify a UDP checksum, pseudo-header included, as a receiver would. */
    private static int udpChecksumOf(byte[] packet) {
        int udpLength = u16(packet, 24);
        int sum = 0;
        for (int i = 12; i < 20; i += 2) {
            sum += u16(packet, i);
        }
        sum += 17;
        sum += udpLength;
        for (int i = 0; i < udpLength - 1; i += 2) {
            sum += u16(packet, 20 + i);
        }
        if ((udpLength & 1) != 0) {
            sum += (packet[20 + udpLength - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }
}
