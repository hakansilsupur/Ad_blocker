package io.github.hakansilsupur.adblock;

import java.util.Locale;

/**
 * A guess at whether a hostname belongs to advertising or tracking.
 *
 * <p>Used only to highlight rows in the allowed-lookups list, never to block
 * anything: the cost of a wrong guess is a misleading colour, not a broken
 * app. It exists because spotting the ad server in a list of forty hostnames
 * otherwise means recognising names like {@code bigossp.com} on sight.
 *
 * <p>Matching is per label rather than over the whole string, because a bare
 * substring search is a trap -- "download" ends in "ad", and "broadcast" and
 * "loadbalancer" contain it too.
 */
public final class AdHints {

    /** Whole labels that give the game away: ads.example.com, track.example.com. */
    private static final String[] TELLTALE_LABELS = {
        "ad", "ads", "adv", "adx", "ssp", "dsp", "rtb", "bid", "bidder", "banner",
        "adserver", "adservice", "adserving", "adsdk", "adsystem", "admin-ads",
        "track", "tracker", "tracking", "telemetry", "metrics", "analytics",
        "stats", "pixel", "impression", "promo", "sponsor", "creatives",
    };

    /** Fragments distinctive enough to match anywhere in the name. */
    private static final String[] TELLTALE_FRAGMENTS = {
        "advert", "adserv", "adsdk", "adsystem", "adnxs", "adcolony", "admob",
        "doubleclick", "syndicat", "googleads", "googlesyndication",
        "analytic", "telemetr", "-ads.", ".ads.", "adtech", "admost", "adjoe",
        "applovin", "applvn", "unityads", "vungle", "chartboost", "ironsrc",
        "ironsource", "mintegral", "pangle", "pangolin", "csjplatform", "moloco",
        "bigossp", "smadex", "ogury", "inmobi", "startapp", "tapjoy", "fyber",
        "appodeal", "bidmachine", "pubmatic", "rubicon", "openx", "criteo",
        "taboola", "outbrain", "appsflyer", "kochava", "adsrvr", "adform",
        "smaato", "supersonic", "yieldmo", "teads", "liftoff", "reklam",
    };

    private AdHints() {
    }

    /** True if the hostname looks like advertising or tracking infrastructure. */
    public static boolean looksLikeAd(String domain) {
        if (domain == null || domain.length() == 0) {
            return false;
        }
        String name = domain.toLowerCase(Locale.US);

        for (int i = 0; i < TELLTALE_FRAGMENTS.length; i++) {
            if (name.contains(TELLTALE_FRAGMENTS[i])) {
                return true;
            }
        }

        String[] labels = name.split("\\.");
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            for (int j = 0; j < TELLTALE_LABELS.length; j++) {
                if (label.equals(TELLTALE_LABELS[j])) {
                    return true;
                }
            }
            // "ads4", "ad2", "adserver-eu" and friends.
            if (label.startsWith("ads") || label.startsWith("adserv")
                    || label.startsWith("adsdk") || label.startsWith("adx")) {
                return true;
            }
        }
        return false;
    }
}
