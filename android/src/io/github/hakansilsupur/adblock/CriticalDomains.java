package io.github.hakansilsupur.adblock;

import java.util.Locale;

/**
 * Names that quietly run the phone, and what blocking them costs.
 *
 * <p>Tap-to-block makes it one gesture to break something that will never be
 * connected back to this app: sinkhole {@code mtalk.google.com} and every
 * push notification on the device stops, days before anyone wonders why. The
 * lookups list shows these names right beside the ad servers, so they need a
 * warning rather than the usual confirmation.
 *
 * <p>This never prevents a block -- it is the user's phone. It only makes
 * sure the consequence is stated before the tap, not discovered afterwards.
 */
public final class CriticalDomains {

    private static final String[][] CRITICAL = {
        // Push. Losing this is invisible until messages stop arriving.
        {"mtalk.google.com", "push notifications"},
        {"fcm.googleapis.com", "push notifications"},
        {"firebaseinstallations.googleapis.com", "push notifications"},
        {"android.apis.google.com", "push notifications"},

        // Play services, updates and licence checks.
        {"android.clients.google.com", "app installs and updates"},
        {"play.googleapis.com", "app installs and updates"},
        {"play.google.com", "the Play Store"},

        // Captive portals and connectivity checks: block these and Android
        // decides the Wi-Fi has no internet and falls back to mobile data.
        {"connectivitycheck.gstatic.com", "Wi-Fi connectivity checks"},
        {"connectivitycheck.android.com", "Wi-Fi connectivity checks"},
        {"clients3.google.com", "Wi-Fi connectivity checks"},

        // Clock and certificate validity depend on these.
        {"time.android.com", "the phone's clock"},
        {"time.google.com", "the phone's clock"},

        // Safe Browsing protects the browser from phishing.
        {"safebrowsing.googleapis.com", "phishing protection"},

        // Broad Google and Apple service endpoints: too much rides on them.
        {"googleapis.com", "many Google app features"},
        {"gstatic.com", "many Google app features"},
        {"icloud.com", "Apple services"},
        {"push.apple.com", "Apple push notifications"},
    };

    private CriticalDomains() {
    }

    /**
     * What breaks if this domain is blocked, or null if nothing known does.
     * Matches the name and its subdomains, as blocking does.
     */
    public static String whatBreaks(String domain) {
        if (domain == null) {
            return null;
        }
        String name = Blocklist.normalize(domain);
        for (int i = 0; i < CRITICAL.length; i++) {
            String critical = CRITICAL[i][0];
            if (name.equals(critical) || name.endsWith("." + critical)) {
                return CRITICAL[i][1];
            }
        }
        return null;
    }

    public static boolean isCritical(String domain) {
        return whatBreaks(domain) != null;
    }
}
