package io.github.hakansilsupur.adblock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Domain blocklist with the same semantics as the desktop resolver's
 * {@code adblock/blocklist.py}: suffix matching, and allow rules that always
 * beat block rules.
 *
 * <p>Blocking {@code example.com} also blocks {@code ads.example.com}, so one
 * entry covers a whole tree. Lookups walk from the queried name upwards and
 * stop at the first rule they meet, which means a nearer allow rule wins over
 * a broader block rule.
 */
public final class Blocklist {

    /** Names that must never be sinkholed, whatever a downloaded list says. */
    private static final String[] NEVER_BLOCK = {
        "localhost", "localhost.localdomain", "local", "broadcasthost",
    };

    private final Set<String> blocked = new HashSet<String>();
    private final Set<String> allowed = new HashSet<String>();

    public int size() {
        return blocked.size();
    }

    public int allowedSize() {
        return allowed.size();
    }

    /** The blocked domains themselves, for merging several parsed lists. */
    public Set<String> blockedDomains() {
        return blocked;
    }

    public static String normalize(String domain) {
        if (domain == null) {
            return "";
        }
        String out = domain.trim().toLowerCase(Locale.US);
        while (out.endsWith(".")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static boolean isValid(String domain) {
        if (domain.length() == 0 || domain.length() > 253 || domain.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!ok) {
                return false;
            }
        }
        for (int i = 0; i < NEVER_BLOCK.length; i++) {
            if (NEVER_BLOCK[i].equals(domain)) {
                return false;
            }
        }
        return true;
    }

    public boolean block(String domain) {
        String name = normalize(domain);
        if (!isValid(name)) {
            return false;
        }
        blocked.add(name);
        return true;
    }

    public boolean allow(String domain) {
        String name = normalize(domain);
        if (!isValid(name)) {
            return false;
        }
        allowed.add(name);
        return true;
    }

    /**
     * True if {@code domain} should be sinkholed. Walks the name and each of
     * its parents, returning on the first rule found.
     */
    public boolean isBlocked(String domain) {
        String name = normalize(domain);
        if (name.length() == 0) {
            return false;
        }
        int start = 0;
        while (true) {
            String candidate = start == 0 ? name : name.substring(start);
            if (allowed.contains(candidate)) {
                return false;
            }
            if (blocked.contains(candidate)) {
                return true;
            }
            int dot = name.indexOf('.', start);
            if (dot < 0) {
                return false;
            }
            start = dot + 1;
        }
    }

    /**
     * Apply one line of a list file. Understands hosts format, plain domains
     * and the Adblock Plus rules that can be expressed as a domain.
     */
    public void addRule(String rawLine) {
        String line = rawLine.trim();
        if (line.length() == 0) {
            return;
        }
        char first = line.charAt(0);
        if (first == '#' || first == '!' || first == '[') {
            return;
        }

        int comment = line.indexOf(" #");
        if (comment < 0) {
            comment = line.indexOf("\t#");
        }
        if (comment > 0) {
            line = line.substring(0, comment).trim();
        }

        if (line.startsWith("@@")) {
            addAbpRule(line.substring(2), true);
            return;
        }
        if (line.startsWith("||")) {
            addAbpRule(line, false);
            return;
        }
        if (line.startsWith("*.")) {
            line = line.substring(2);
        }
        if (line.indexOf('|') >= 0 || line.indexOf('^') >= 0 || line.indexOf('*') >= 0
                || line.indexOf('$') >= 0 || line.indexOf('/') >= 0) {
            return;
        }

        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            if (!isSinkholeAddress(parts[0])) {
                return; // a real hosts entry, not an ad rule
            }
            for (int i = 1; i < parts.length; i++) {
                block(parts[i]);
            }
        } else if (parts.length == 1) {
            block(parts[0]);
        }
    }

    private static boolean isSinkholeAddress(String address) {
        return address.equals("0.0.0.0") || address.equals("127.0.0.1")
                || address.equals("::") || address.equals("::1")
                || address.equals("0:0:0:0:0:0:0:0");
    }

    /** Handles {@code ||domain^$options}; only whole-domain rules are usable. */
    private void addAbpRule(String rule, boolean isAllow) {
        if (!rule.startsWith("||")) {
            return;
        }
        String body = rule.substring(2);
        int dollar = body.indexOf('$');
        if (dollar >= 0) {
            String options = body.substring(dollar + 1);
            body = body.substring(0, dollar);
            if (!optionsAreDomainWide(options)) {
                return; // scoped to a resource type or site: not a DNS decision
            }
        }
        while (body.endsWith("^") || body.endsWith("/")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.length() == 0 || body.indexOf('/') >= 0 || body.indexOf('*') >= 0
                || body.indexOf('^') >= 0 || body.indexOf('|') >= 0) {
            return;
        }
        if (isAllow) {
            allow(body);
        } else {
            block(body);
        }
    }

    private static boolean optionsAreDomainWide(String options) {
        String[] parts = options.split(",");
        for (int i = 0; i < parts.length; i++) {
            String option = parts[i].trim();
            if (option.startsWith("~")) {
                option = option.substring(1);
            }
            boolean wide = option.equals("all") || option.equals("document")
                    || option.equals("popup") || option.equals("third-party")
                    || option.equals("3p") || option.equals("doc");
            if (!wide) {
                return false;
            }
        }
        return true;
    }

    /** Read rules from a stream. The stream is closed by the caller. */
    public int load(InputStream input) throws IOException {
        int before = blocked.size();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"), 32768);
        String line;
        while ((line = reader.readLine()) != null) {
            addRule(line);
        }
        return blocked.size() - before;
    }
}
