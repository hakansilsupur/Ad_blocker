package io.github.hakansilsupur.adblock;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * The single screen: a switch, the counters, and what was blocked recently.
 *
 * <p>State is read straight from {@link Stats} on a one-second tick rather
 * than pushed from the service. The service and the activity share a process,
 * and polling four atomics is cheaper than the plumbing to avoid it.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_VPN_PERMISSION = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final long REFRESH_INTERVAL_MS = 1000;

    /** Named directly: the constant only exists from API 33 onwards. */
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private Button toggleButton;
    private Button updateButton;
    private TextView statusText;
    private TextView blockedCount;
    private TextView queryCount;
    private TextView listInfo;
    private TextView recentList;
    private android.widget.LinearLayout allowedList;

    /** What the allowed-list is currently showing, to avoid rebuilding it. */
    private List<String> shownAllowed = java.util.Collections.emptyList();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toggleButton = (Button) findViewById(R.id.toggle);
        updateButton = (Button) findViewById(R.id.update);
        statusText = (TextView) findViewById(R.id.status);
        blockedCount = (TextView) findViewById(R.id.blocked_count);
        queryCount = (TextView) findViewById(R.id.query_count);
        listInfo = (TextView) findViewById(R.id.list_info);
        recentList = (TextView) findViewById(R.id.recent);
        allowedList = (android.widget.LinearLayout) findViewById(R.id.allowed_list);

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onToggleClicked();
            }
        });
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onUpdateClicked();
            }
        });
        findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shareAllowedList();
            }
        });

        if (Stats.listSize.get() == 0) {
            // Show the list size before the service has ever run.
            loadListSizeInBackground();
        }
        askForNotificationPermission();
    }

    /**
     * From Android 13 the ongoing "protection is on" notification needs
     * permission. Blocking still works without it; the status just stays
     * hidden, so this asks once and does not insist.
     */
    private void askForNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void onToggleClicked() {
        if (Stats.running.get()) {
            DnsVpnService.stop(this);
            return;
        }
        // The system asks the user to confirm the VPN the first time.
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN_PERMISSION);
        } else {
            onActivityResult(REQUEST_VPN_PERMISSION, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                Stats.reset();
                DnsVpnService.start(this);
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void onUpdateClicked() {
        updateButton.setEnabled(false);
        updateButton.setText(R.string.updating);

        new Thread(new Runnable() {
            @Override
            public void run() {
                Lists.update(MainActivity.this, new Lists.Progress() {
                    @Override
                    public void onSource(final String name, final int index,
                            final int total, final boolean ok) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                updateButton.setText(
                                        getString(R.string.updating_source, index, total, name));
                            }
                        });
                    }

                    @Override
                    public void onFinished(final int domains, final String error) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                updateButton.setEnabled(true);
                                updateButton.setText(R.string.update_lists);
                                if (error != null) {
                                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG)
                                            .show();
                                    return;
                                }
                                Stats.listSize.set(domains);
                                Toast.makeText(
                                        MainActivity.this,
                                        getString(R.string.update_done, domains),
                                        Toast.LENGTH_LONG).show();
                                // Pick the new list up without dropping the tunnel.
                                DnsVpnService.reload(MainActivity.this);
                                render();
                            }
                        });
                    }
                });
            }
        }, "adblock-list-update").start();
    }

    /** Post to the UI thread only while this activity is still around. */
    private void post(Runnable action) {
        if (!isFinishing()) {
            handler.post(action);
        }
    }

    private void loadListSizeInBackground() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int size = Lists.load(MainActivity.this).size();
                post(new Runnable() {
                    @Override
                    public void run() {
                        Stats.listSize.set(size);
                        render();
                    }
                });
            }
        }, "adblock-list-count").start();
    }

    private void render() {
        boolean running = Stats.running.get();
        toggleButton.setText(running ? R.string.stop : R.string.start);
        statusText.setText(running ? R.string.status_on : R.string.status_off);
        statusText.setTextColor(
                getResources().getColor(running ? R.color.accent : R.color.muted));

        blockedCount.setText(String.valueOf(Stats.blocked.get()));
        queryCount.setText(String.valueOf(Stats.queries.get()));

        int size = Stats.listSize.get();
        long updated = Prefs.listUpdatedAt(this);
        String when = updated == 0
                ? getString(R.string.list_bundled)
                : DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(updated));
        listInfo.setText(getString(R.string.list_info, size, when, Stats.blockedPercent()));

        List<String> recent = Stats.recentBlocked();
        recentList.setText(recent.isEmpty()
                ? getString(R.string.nothing_blocked_yet)
                : TextUtils.join("\n", recent));

        renderAllowed();
    }

    /**
     * List the domains that were allowed through, each one tappable.
     *
     * <p>When an app still shows ads, the domain serving them is in this list.
     * Tapping it blocks it, which is far more reliable than trying to guess
     * which network a given app happens to use.
     */
    private void renderAllowed() {
        final List<String> domains = Stats.recentAllowed();
        if (domains.equals(shownAllowed)) {
            return; // nothing changed; leave the rows alone
        }
        shownAllowed = domains;
        allowedList.removeAllViews();

        if (domains.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.nothing_allowed_yet);
            empty.setTextColor(getResources().getColor(R.color.muted));
            empty.setTextSize(12);
            allowedList.addView(empty);
            return;
        }

        for (int i = 0; i < domains.size(); i++) {
            final String domain = domains.get(i);
            TextView row = new TextView(this);
            boolean suspicious = AdHints.looksLikeAd(domain);
            // Colour the likely ad servers so the culprit stands out without
            // the reader having to recognise ad-network names.
            row.setText(suspicious ? "●  " + domain : "    " + domain);
            row.setTextColor(getResources().getColor(
                    suspicious ? R.color.accent : R.color.muted));
            row.setTextSize(13);
            row.setPadding(0, 14, 0, 14);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmBlock(domain);
                }
            });
            allowedList.addView(row);
        }
    }

    /**
     * Share the allowed lookups as plain text.
     *
     * <p>Reading forty hostnames off a phone screen and retyping them is
     * miserable, and it is exactly what someone has to do to report "this app
     * still shows ads". One tap sends the list somewhere it can be pasted.
     */
    private void shareAllowedList() {
        List<String> domains = Stats.recentAllowed();
        if (domains.isEmpty()) {
            Toast.makeText(this, R.string.nothing_allowed_yet, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder text = new StringBuilder(512);
        text.append("AdBlock - allowed lookups (newest first)\n");
        text.append("blocked ").append(Stats.blocked.get())
                .append(" of ").append(Stats.queries.get()).append(" lookups, ")
                .append(Stats.listSize.get()).append(" domains on the list\n\n");
        for (int i = 0; i < domains.size(); i++) {
            String domain = domains.get(i);
            // Mark the suspicious ones so the list is readable by whoever
            // receives it, not just on screen.
            text.append(AdHints.looksLikeAd(domain) ? "* " : "  ")
                    .append(domain)
                    .append('\n');
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject));
        share.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(share, getString(R.string.share_list)));
    }

    private void confirmBlock(final String domain) {
        // Say plainly what a dangerous block costs, before it is made.
        String breaks = CriticalDomains.whatBreaks(domain);
        String message = breaks == null
                ? getString(R.string.block_message)
                : getString(R.string.block_message_critical, breaks);

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.block_title, domain))
                .setMessage(message)
                .setPositiveButton(R.string.block_confirm,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    android.content.DialogInterface dialog, int which) {
                                blockDomain(domain);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void blockDomain(final String domain) {
        // File write and list reload both stay off the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Lists.addUserBlock(MainActivity.this, domain);
                if (ok) {
                    Stats.forgetAllowed(domain);
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(
                                MainActivity.this,
                                getString(ok ? R.string.block_done : R.string.block_failed,
                                        domain),
                                Toast.LENGTH_SHORT).show();
                        if (ok) {
                            DnsVpnService.reload(MainActivity.this);
                            render();
                        }
                    }
                });
            }
        }, "adblock-user-block").start();
    }
}
