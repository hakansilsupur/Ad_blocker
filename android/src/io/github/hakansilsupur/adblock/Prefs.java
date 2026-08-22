package io.github.hakansilsupur.adblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.InetSocketAddress;

/** Stored settings. Currently just the upstream resolvers. */
public final class Prefs {

    private static final String FILE = "adblock";
    private static final String KEY_UPSTREAMS = "upstreams";
    private static final String KEY_LIST_UPDATED = "list_updated";

    /** Cloudflare and Quad9. Change these to any resolver you trust. */
    private static final String DEFAULT_UPSTREAMS = "1.1.1.1,9.9.9.9";

    // Read on every relayed query, so it is cached rather than re-parsed.
    private static volatile String[] cachedUpstreams;

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String[] upstreams(Context context) {
        String[] cached = cachedUpstreams;
        if (cached != null) {
            return cached;
        }
        String value = prefs(context).getString(KEY_UPSTREAMS, DEFAULT_UPSTREAMS);
        String[] parsed = value.split(",");
        for (int i = 0; i < parsed.length; i++) {
            parsed[i] = parsed[i].trim();
        }
        cachedUpstreams = parsed;
        return parsed;
    }

    public static void setUpstreams(Context context, String value) {
        prefs(context).edit().putString(KEY_UPSTREAMS, value).apply();
        cachedUpstreams = null;
    }

    public static long listUpdatedAt(Context context) {
        return prefs(context).getLong(KEY_LIST_UPDATED, 0L);
    }

    public static void setListUpdatedAt(Context context, long millis) {
        prefs(context).edit().putLong(KEY_LIST_UPDATED, millis).apply();
    }

    /** Parse {@code host} or {@code host:port}, defaulting to port 53. */
    public static InetSocketAddress parseUpstream(String value) {
        String text = value.trim();
        int colon = text.lastIndexOf(':');
        // A bare IPv6 literal has several colons and no port.
        if (colon > 0 && text.indexOf(':') == colon) {
            try {
                return InetSocketAddress.createUnresolved(
                        text.substring(0, colon), Integer.parseInt(text.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
                // Fall through and treat the whole string as a host.
            }
        }
        return InetSocketAddress.createUnresolved(text, 53);
    }
}
