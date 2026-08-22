package io.github.hakansilsupur.adblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local VPN that filters DNS, which is how ads get blocked in every app on
 * the device rather than only in a browser.
 *
 * <p>The tunnel is deliberately narrow: it advertises one fake DNS server and
 * routes <em>only</em> that address, so nothing else the phone does is touched
 * by this app. Everything but DNS takes its normal path and never enters this
 * process.
 *
 * <pre>
 *   app asks for ads.example.com
 *        -> system sends the query to 10.111.222.2 (our fake resolver)
 *        -> kernel routes it into the tun device
 *        -> this service reads the packet, reads the question
 *              blocked -> answer 0.0.0.0 immediately, nothing leaves the phone
 *              allowed -> relay to the real upstream over a protected socket
 * </pre>
 */
public class DnsVpnService extends VpnService {

    public static final String ACTION_START = "io.github.hakansilsupur.adblock.START";
    public static final String ACTION_STOP = "io.github.hakansilsupur.adblock.STOP";
    public static final String ACTION_RELOAD = "io.github.hakansilsupur.adblock.RELOAD";

    private static final String TAG = "AdBlockVpn";
    private static final String CHANNEL_ID = "adblock_vpn";
    private static final int NOTIFICATION_ID = 1;

    /** Addresses inside the tunnel. Private range, unused by real networks. */
    private static final String TUN_ADDRESS = "10.111.222.1";
    private static final String TUN_DNS_SERVER = "10.111.222.2";

    /**
     * Roomy enough for EDNS responses, so clients rarely need to fall back to
     * TCP -- which this tunnel does not carry.
     */
    private static final int MTU = 4096;

    private static final int BLOCK_TTL_SECONDS = 60;
    private static final int UPSTREAM_TIMEOUT_MS = 5000;
    private static final int RELAY_THREADS = 8;

    private ParcelFileDescriptor tunnel;
    private Thread readerThread;
    private ExecutorService relayPool;
    private FileOutputStream tunnelOut;
    private volatile boolean running;
    private volatile Blocklist blocklist = new Blocklist();
    private final AtomicInteger packetId = new AtomicInteger(1);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            reloadInBackground();
            return START_STICKY;
        }
        startTunnel();
        return START_STICKY;
    }

    /**
     * Bring the tunnel up.
     *
     * <p>The foreground notification is posted first and synchronously, so the
     * system sees a foreground service immediately. Everything after it --
     * parsing a list that can run to 150,000 domains, then establishing the
     * interface -- happens on a worker thread, because doing that work on the
     * main thread is a visible freeze and, on a slow phone, an ANR.
     */
    private void startTunnel() {
        if (running) {
            return;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        new Thread(new Runnable() {
            @Override
            public void run() {
                establishTunnel();
            }
        }, "adblock-start").start();
    }

    private void establishTunnel() {
        loadBlocklist();
        try {
            Builder builder = new Builder();
            builder.setSession(getString(R.string.app_name));
            builder.addAddress(TUN_ADDRESS, 32);
            builder.addDnsServer(TUN_DNS_SERVER);
            // Route the fake resolver and nothing else: only DNS enters the tun.
            builder.addRoute(TUN_DNS_SERVER, 32);
            builder.setMtu(MTU);
            builder.setBlocking(true);
            try {
                // Our own upstream lookups must not re-enter the tunnel.
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {
                // Not fatal: protect() already keeps the relay socket outside.
            }
            builder.setConfigureIntent(
                    PendingIntent.getActivity(
                            this,
                            0,
                            new Intent(this, MainActivity.class),
                            pendingIntentFlags()));

            tunnel = builder.establish();
            if (tunnel == null) {
                Log.w(TAG, "VPN permission was revoked before the tunnel came up");
                stopSelf();
                return;
            }
            tunnelOut = new FileOutputStream(tunnel.getFileDescriptor());
            relayPool = Executors.newFixedThreadPool(RELAY_THREADS);
            running = true;
            Stats.running.set(true);

            readerThread = new Thread(new PacketReader(tunnel), "adblock-tun-reader");
            readerThread.start();
            // Now that the list is loaded, say how big it is.
            refreshNotification();
            Log.i(TAG, "tunnel up, " + blocklist.size() + " domains blocked");
        } catch (Exception e) {
            Log.e(TAG, "could not start the tunnel", e);
            stopTunnel();
            stopSelf();
        }
    }

    private void stopTunnel() {
        running = false;
        Stats.running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (relayPool != null) {
            relayPool.shutdownNow();
            relayPool = null;
        }
        closeQuietly();
        stopForeground(true);
    }

    private void closeQuietly() {
        try {
            if (tunnelOut != null) {
                tunnelOut.close();
            }
        } catch (IOException ignored) {
            // Closing a tunnel that is already gone is not interesting.
        }
        tunnelOut = null;
        try {
            if (tunnel != null) {
                tunnel.close();
            }
        } catch (IOException ignored) {
            // As above.
        }
        tunnel = null;
    }

    @Override
    public void onRevoke() {
        // Another VPN app took over, or the user revoked permission.
        Log.i(TAG, "VPN permission revoked");
        stopTunnel();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    /** Load the downloaded list if there is one, otherwise the bundled seed. */
    private void loadBlocklist() {
        Blocklist loaded = Lists.load(this);
        blocklist = loaded;
        Stats.listSize.set(loaded.size());
    }

    /** Swap in a freshly downloaded list without dropping the tunnel. */
    private void reloadInBackground() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                loadBlocklist();
                refreshNotification();
            }
        }, "adblock-reload").start();
    }

    private void refreshNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && running) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    // ---------------------------------------------------------------- reading

    private final class PacketReader implements Runnable {
        // Held directly rather than read from the field, which stop() clears.
        private final ParcelFileDescriptor descriptor;

        PacketReader(ParcelFileDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public void run() {
            FileInputStream in = new FileInputStream(descriptor.getFileDescriptor());
            byte[] buffer = new byte[MTU];
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    int length = in.read(buffer);
                    if (length <= 0) {
                        continue;
                    }
                    byte[] packet = new byte[length];
                    System.arraycopy(buffer, 0, packet, 0, length);
                    handlePacket(packet, length);
                } catch (IOException e) {
                    if (running) {
                        Log.w(TAG, "tunnel read failed", e);
                    }
                    break;
                }
            }
        }
    }

    private void handlePacket(byte[] packet, int length) {
        // The tunnel routes only UDP DNS, but check rather than trust.
        if (!IpPacket.isUdp(packet, length) || IpPacket.isFragment(packet)) {
            return;
        }
        if (IpPacket.destinationPort(packet) != 53) {
            return;
        }

        int payloadOffset = IpPacket.payloadOffset(packet);
        int payloadLength = IpPacket.payloadLength(packet, length);
        if (payloadLength <= 0) {
            return;
        }

        DnsMessage.Question question =
                DnsMessage.parseQuestion(packet, payloadOffset, payloadLength);
        if (question == null) {
            Stats.recordError();
            return;
        }

        if (blocklist.isBlocked(question.name)) {
            Stats.recordBlocked(question.name, question.type);
            byte[] answer = DnsMessage.buildSinkholeResponse(
                    packet, payloadOffset, payloadLength, question, BLOCK_TTL_SECONDS);
            writeToTunnel(IpPacket.buildUdpReply(packet, answer, packetId.getAndIncrement()));
            return;
        }

        ExecutorService pool = relayPool;
        if (pool == null) {
            return;
        }
        byte[] query = new byte[payloadLength];
        System.arraycopy(packet, payloadOffset, query, 0, payloadLength);
        try {
            pool.execute(new Relay(packet, query, question));
        } catch (RuntimeException e) {
            // Pool shutting down, or saturated: the client will retry.
            Stats.recordError();
        }
    }

    // -------------------------------------------------------------- relaying

    /** Sends one allowed query upstream and writes the answer back. */
    private final class Relay implements Runnable {
        private final byte[] requestPacket;
        private final byte[] query;
        private final DnsMessage.Question question;

        Relay(byte[] requestPacket, byte[] query, DnsMessage.Question question) {
            this.requestPacket = requestPacket;
            this.query = query;
            this.question = question;
        }

        @Override
        public void run() {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                // Keep this socket outside the tunnel, or it would loop back in.
                if (!protect(socket)) {
                    Stats.recordError();
                    return;
                }
                socket.setSoTimeout(UPSTREAM_TIMEOUT_MS);

                byte[] response = null;
                String[] upstreams = Prefs.upstreams(DnsVpnService.this);
                for (int i = 0; i < upstreams.length && response == null; i++) {
                    response = ask(socket, upstreams[i]);
                }
                if (response == null) {
                    Stats.recordError();
                    return;
                }

                Stats.recordForwarded(question.name);
                byte[] answer = response;
                if (answer.length > IpPacket.maxPayload(MTU)) {
                    // Too big for the tunnel: tell the client to use TCP.
                    answer = DnsMessage.buildTruncatedResponse(
                            requestPacket, IpPacket.payloadOffset(requestPacket), question);
                }
                writeToTunnel(
                        IpPacket.buildUdpReply(requestPacket, answer, packetId.getAndIncrement()));
            } catch (IOException e) {
                Stats.recordError();
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }

        private byte[] ask(DatagramSocket socket, String upstream) {
            try {
                InetSocketAddress address = Prefs.parseUpstream(upstream);
                InetAddress host = InetAddress.getByName(address.getHostString());
                socket.send(new DatagramPacket(query, query.length, host, address.getPort()));

                byte[] buffer = new byte[MTU];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                socket.receive(reply);
                byte[] out = new byte[reply.getLength()];
                System.arraycopy(buffer, 0, out, 0, reply.getLength());
                return out;
            } catch (IOException e) {
                return null; // try the next upstream
            }
        }
    }

    /** Writes are serialised: several relay threads share one tunnel. */
    private void writeToTunnel(byte[] packet) {
        FileOutputStream out = tunnelOut;
        if (out == null) {
            return;
        }
        try {
            synchronized (this) {
                out.write(packet);
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "tunnel write failed", e);
            }
        }
    }

    // ---------------------------------------------------------- notification

    private Notification buildNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), pendingIntentFlags());

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text, blocklist.size()))
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private static int pendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    /** Convenience for the activity: start or stop without knowing the actions. */
    public static void start(Context context) {
        Intent intent = new Intent(context, DnsVpnService.class).setAction(ACTION_START);
        context.startService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, DnsVpnService.class).setAction(ACTION_STOP));
    }

    public static void reload(Context context) {
        if (Stats.running.get()) {
            context.startService(
                    new Intent(context, DnsVpnService.class).setAction(ACTION_RELOAD));
        }
    }

    /** Waits briefly for the reader thread, used only by instrumentation. */
    boolean awaitStopped(long millis) throws InterruptedException {
        Thread thread = readerThread;
        if (thread == null) {
            return true;
        }
        thread.join(TimeUnit.MILLISECONDS.toMillis(millis));
        return !thread.isAlive();
    }
}
