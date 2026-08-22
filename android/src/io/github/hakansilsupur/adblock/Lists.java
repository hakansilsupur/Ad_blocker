package io.github.hakansilsupur.adblock;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Where the app's domain lists come from.
 *
 * <p>The APK ships the same curated seed list the desktop resolver uses, so
 * blocking works the moment it is installed. "Update lists" then downloads the
 * same public sources the desktop {@code adblock update} uses and stores the
 * result in the app's private files, which takes precedence from then on.
 */
public final class Lists {

    private static final String TAG = "AdBlockLists";

    /** Bundled in the APK from the repository's lists/ directory. */
    private static final String ASSET_BLOCKLIST = "blocklist.txt";
    private static final String ASSET_ALLOWLIST = "allowlist.txt";

    /** Written by an update; preferred over the bundled copy when present. */
    private static final String DOWNLOADED_BLOCKLIST = "blocklist.txt";
    /** The user's own exceptions, which survive every update. */
    public static final String USER_ALLOWLIST = "allowlist.txt";

    /** Domains the user blocked by hand, which also survive every update. */
    public static final String USER_BLOCKLIST = "userblock.txt";

    /** The same sources the desktop updater uses. */
    public static final String[] SOURCES = {
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt",
        "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/adblock.txt",
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master"
                + "/SpywareFilter/sections/tracking_servers.txt",
    };

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;

    private Lists() {
    }

    /** Build the blocklist the service should use right now. */
    public static Blocklist load(Context context) {
        Blocklist blocklist = new Blocklist();

        File downloaded = new File(context.getFilesDir(), DOWNLOADED_BLOCKLIST);
        boolean loaded = false;
        if (downloaded.isFile() && downloaded.length() > 0) {
            loaded = loadFile(blocklist, downloaded);
        }
        if (!loaded) {
            loadAsset(context, blocklist, ASSET_BLOCKLIST);
        }

        // The user's own blocks are added before the allow rules, so an
        // explicit exception can still override one.
        File userBlocklist = new File(context.getFilesDir(), USER_BLOCKLIST);
        if (userBlocklist.isFile()) {
            loadFile(blocklist, userBlocklist);
        }

        // Allow rules are applied last so they win over everything above.
        loadAsset(context, blocklist, ASSET_ALLOWLIST);
        File userAllowlist = new File(context.getFilesDir(), USER_ALLOWLIST);
        if (userAllowlist.isFile()) {
            loadAllowFile(blocklist, userAllowlist);
        }
        return blocklist;
    }

    /**
     * Add one domain to the user's own blocklist.
     *
     * <p>Written to a separate file from the downloaded list so that updating
     * the public lists never discards it.
     *
     * @return true if it was written
     */
    public static boolean addUserBlock(Context context, String domain) {
        String name = Blocklist.normalize(domain);
        if (name.length() == 0) {
            return false;
        }
        File file = new File(context.getFilesDir(), USER_BLOCKLIST);
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(
                    new java.io.FileOutputStream(file, true), "UTF-8");
            writer.write(name);
            writer.write('\n');
            return true;
        } catch (IOException e) {
            Log.w(TAG, "could not add " + name + " to the user blocklist", e);
            return false;
        } finally {
            closeQuietly(writer);
        }
    }

    private static boolean loadFile(Blocklist blocklist, File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            blocklist.load(in);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "could not read " + file, e);
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private static void loadAsset(Context context, Blocklist blocklist, String name) {
        InputStream in = null;
        try {
            in = context.getAssets().open(name);
            blocklist.load(in);
        } catch (IOException e) {
            Log.w(TAG, "bundled list " + name + " is missing", e);
        } finally {
            closeQuietly(in);
        }
    }

    /** Every line of the user's allowlist is an allow rule, comments aside. */
    private static void loadAllowFile(Blocklist blocklist, File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() > 0 && !trimmed.startsWith("#")) {
                    blocklist.allow(trimmed);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read the user allowlist", e);
        } finally {
            closeQuietly(reader);
        }
    }

    /** Progress reporting for the update, so the screen can say what is happening. */
    public interface Progress {
        void onSource(String name, int index, int total, boolean ok);

        void onFinished(int domains, String error);
    }

    /**
     * Download every source, merge them, and store the result. Runs on the
     * caller's thread, which must not be the main thread.
     *
     * @return the number of domains written, or -1 if nothing could be fetched
     */
    public static int update(Context context, Progress progress) {
        Set<String> domains = new HashSet<String>(200000);
        int fetched = 0;

        for (int i = 0; i < SOURCES.length; i++) {
            String url = SOURCES[i];
            String name = shortName(url);
            Blocklist source = new Blocklist();
            boolean ok = download(url, source);
            if (ok) {
                fetched++;
                domains.addAll(source.blockedDomains());
            }
            if (progress != null) {
                progress.onSource(name, i + 1, SOURCES.length, ok);
            }
        }

        if (fetched == 0) {
            if (progress != null) {
                progress.onFinished(0, context.getString(R.string.update_failed));
            }
            return -1;
        }

        int written = write(context, domains);
        if (written > 0) {
            Prefs.setListUpdatedAt(context, System.currentTimeMillis());
        }
        if (progress != null) {
            progress.onFinished(written, null);
        }
        return written;
    }

    private static boolean download(String url, Blocklist into) {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "adblock-android/0.1");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, url + " returned " + connection.getResponseCode());
                return false;
            }
            in = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            into.load(in);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "could not fetch " + url, e);
            return false;
        } finally {
            closeQuietly(in);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Write to a temporary file first, so a failure cannot truncate the list in use. */
    private static int write(Context context, Set<String> domains) {
        File target = new File(context.getFilesDir(), DOWNLOADED_BLOCKLIST);
        File temporary = new File(context.getFilesDir(), DOWNLOADED_BLOCKLIST + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(
                    new java.io.FileOutputStream(temporary), "UTF-8");
            Iterator<String> iterator = domains.iterator();
            while (iterator.hasNext()) {
                writer.write(iterator.next());
                writer.write('\n');
            }
            writer.flush();
            closeQuietly(writer);
            writer = null;

            if (target.exists() && !target.delete()) {
                Log.w(TAG, "could not replace the existing list");
            }
            if (!temporary.renameTo(target)) {
                Log.w(TAG, "could not move the new list into place");
                return 0;
            }
            return domains.size();
        } catch (IOException e) {
            Log.w(TAG, "could not write the list", e);
            return 0;
        } finally {
            closeQuietly(writer);
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static String shortName(String url) {
        int slash = url.indexOf('/', 8);
        int host = slash > 0 ? slash : url.length();
        String domain = url.substring(url.indexOf("//") + 2, host);
        if (url.contains("StevenBlack")) {
            return "StevenBlack";
        }
        if (url.contains("hagezi")) {
            return "HaGeZi light";
        }
        if (url.contains("1Hosts")) {
            return "1Hosts Lite";
        }
        if (url.contains("Adguard")) {
            return "AdGuard trackers";
        }
        return domain;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failed close.
            }
        }
    }
}
