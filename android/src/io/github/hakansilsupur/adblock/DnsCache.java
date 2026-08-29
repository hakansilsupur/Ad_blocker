package io.github.hakansilsupur.adblock;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A TTL + LRU cache for upstream answers.
 *
 * <p>Without one, every lookup crosses the network -- including the same
 * hostname repeated dozens of times while one screen loads. That turns the
 * blocker into a permanent latency tax on the whole device, and makes a slow
 * or unreachable resolver hurt far more than it should.
 *
 * <p>Entries expire on the answer's own TTL, clamped: the floor stops a
 * one-second TTL from being pointless, and the ceiling keeps a stale address
 * from outliving a change of network by long.
 */
public final class DnsCache {

    /** Never hold an answer for less than this, whatever the record says. */
    public static final int MIN_TTL_SECONDS = 30;

    /** Nor longer than this: phones change networks, and CDNs move. */
    public static final int MAX_TTL_SECONDS = 600;

    private static final class Entry {
        final byte[] response;
        final long expiresAtMs;

        Entry(byte[] response, long expiresAtMs) {
            this.response = response;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final int maxEntries;
    private final LinkedHashMap<String, Entry> entries;

    private long hits;
    private long misses;

    public DnsCache(int maxEntries) {
        this.maxEntries = maxEntries;
        // accessOrder = true makes iteration order least-recently-used first.
        this.entries = new LinkedHashMap<String, Entry>(64, 0.75f, true);
    }

    public static String key(String name, int type, int dnsClass) {
        return name + '|' + type + '|' + dnsClass;
    }

    /**
     * The cached answer for a key, or null. The caller must put the asking
     * query's transaction ID into the reply before sending it on.
     */
    public synchronized byte[] get(String key, long nowMs) {
        Entry entry = entries.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        if (entry.expiresAtMs <= nowMs) {
            entries.remove(key);
            misses++;
            return null;
        }
        hits++;
        return entry.response;
    }

    public synchronized void put(String key, byte[] response, int ttlSeconds, long nowMs) {
        if (response == null || response.length == 0 || ttlSeconds <= 0 || maxEntries <= 0) {
            return;
        }
        int ttl = ttlSeconds;
        if (ttl < MIN_TTL_SECONDS) {
            ttl = MIN_TTL_SECONDS;
        }
        if (ttl > MAX_TTL_SECONDS) {
            ttl = MAX_TTL_SECONDS;
        }
        entries.put(key, new Entry(response, nowMs + ttl * 1000L));

        // Evict the least recently used entries once over capacity.
        if (entries.size() > maxEntries) {
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            while (entries.size() > maxEntries && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    /** Called when the network changes: cached addresses may no longer apply. */
    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }
}
