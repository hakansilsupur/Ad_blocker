package io.github.hakansilsupur.adblock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters shared between the VPN service and the screen showing them.
 *
 * <p>Deliberately a process-wide singleton of plain atomics: the service and
 * the activity live in the same process, and the alternative -- binding to the
 * service just to read four numbers -- buys nothing.
 */
public final class Stats {

    private static final int RECENT_LIMIT = 40;

    public static final AtomicBoolean running = new AtomicBoolean(false);
    public static final AtomicLong queries = new AtomicLong();
    public static final AtomicLong blocked = new AtomicLong();
    public static final AtomicLong forwarded = new AtomicLong();
    public static final AtomicLong errors = new AtomicLong();
    public static final AtomicInteger listSize = new AtomicInteger();

    private static final Deque<String> recent = new ArrayDeque<String>();

    private Stats() {
    }

    public static void recordBlocked(String domain, int type) {
        blocked.incrementAndGet();
        queries.incrementAndGet();
        synchronized (recent) {
            recent.addFirst(domain + "  (" + DnsMessage.typeName(type) + ")");
            while (recent.size() > RECENT_LIMIT) {
                recent.removeLast();
            }
        }
    }

    public static void recordForwarded() {
        forwarded.incrementAndGet();
        queries.incrementAndGet();
    }

    public static void recordError() {
        errors.incrementAndGet();
    }

    public static List<String> recentBlocked() {
        synchronized (recent) {
            return new ArrayList<String>(recent);
        }
    }

    /** Percentage of queries that were blocked, for display. */
    public static int blockedPercent() {
        long total = queries.get();
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(blocked.get() * 100.0 / total);
    }

    public static void reset() {
        queries.set(0);
        blocked.set(0);
        forwarded.set(0);
        errors.set(0);
        synchronized (recent) {
            recent.clear();
        }
    }
}
