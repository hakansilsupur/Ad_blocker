package io.github.hakansilsupur.adblock;

/**
 * Minimal IPv4 + UDP handling for the packets that come off the tun device.
 *
 * <p>The tunnel only routes the one fake DNS server address, so the only
 * packets here are UDP datagrams to port 53. Everything else on the device
 * goes out normally and never reaches this code.
 */
public final class IpPacket {

    public static final int PROTO_UDP = 17;
    private static final int IPV4_HEADER_LEN = 20;
    private static final int UDP_HEADER_LEN = 8;

    private IpPacket() {
    }

    private static int u8(byte[] p, int i) {
        return p[i] & 0xFF;
    }

    private static int u16(byte[] p, int i) {
        return ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
    }

    private static void put16(byte[] p, int i, int value) {
        p[i] = (byte) ((value >> 8) & 0xFF);
        p[i + 1] = (byte) (value & 0xFF);
    }

    public static boolean isIpv4(byte[] packet, int length) {
        return length >= IPV4_HEADER_LEN && (packet[0] & 0xF0) == 0x40;
    }

    public static int headerLength(byte[] packet) {
        return (packet[0] & 0x0F) * 4;
    }

    public static int protocol(byte[] packet) {
        return u8(packet, 9);
    }

    /** True if this is a UDP datagram we can read a full header from. */
    public static boolean isUdp(byte[] packet, int length) {
        if (!isIpv4(packet, length) || protocol(packet) != PROTO_UDP) {
            return false;
        }
        int ihl = headerLength(packet);
        return ihl >= IPV4_HEADER_LEN && length >= ihl + UDP_HEADER_LEN;
    }

    /**
     * True if the packet is fragmented. A fragment holds only part of the
     * datagram, so its UDP payload cannot be read on its own.
     */
    public static boolean isFragment(byte[] packet) {
        int flagsAndOffset = u16(packet, 6);
        boolean moreFragments = (flagsAndOffset & 0x2000) != 0;
        int fragmentOffset = flagsAndOffset & 0x1FFF;
        return moreFragments || fragmentOffset != 0;
    }

    public static int sourcePort(byte[] packet) {
        return u16(packet, headerLength(packet));
    }

    public static int destinationPort(byte[] packet) {
        return u16(packet, headerLength(packet) + 2);
    }

    public static int payloadOffset(byte[] packet) {
        return headerLength(packet) + UDP_HEADER_LEN;
    }

    /** UDP payload length, taken from the UDP header and clamped to what we read. */
    public static int payloadLength(byte[] packet, int length) {
        int declared = u16(packet, headerLength(packet) + 4) - UDP_HEADER_LEN;
        int available = length - payloadOffset(packet);
        if (declared < 0 || declared > available) {
            return available;
        }
        return declared;
    }

    /** Bytes of payload that fit in one datagram at the given MTU. */
    public static int maxPayload(int mtu) {
        return mtu - IPV4_HEADER_LEN - UDP_HEADER_LEN;
    }

    /**
     * Build the reply to {@code request}, swapping the addresses and ports so
     * it appears to come from the server the client asked.
     */
    public static byte[] buildUdpReply(byte[] request, byte[] payload, int identification) {
        int requestHeaderLen = headerLength(request);
        int total = IPV4_HEADER_LEN + UDP_HEADER_LEN + payload.length;
        byte[] out = new byte[total];

        out[0] = 0x45;                              // IPv4, 5 x 32-bit words of header
        out[1] = 0;                                 // no differentiated services
        put16(out, 2, total);
        put16(out, 4, identification & 0xFFFF);
        put16(out, 6, 0x4000);                      // don't fragment
        out[8] = 64;                                // TTL
        out[9] = (byte) PROTO_UDP;
        // Source is the request's destination (our fake resolver) and vice versa.
        System.arraycopy(request, 16, out, 12, 4);
        System.arraycopy(request, 12, out, 16, 4);
        put16(out, 10, checksum(out, 0, IPV4_HEADER_LEN));

        int udp = IPV4_HEADER_LEN;
        put16(out, udp, u16(request, requestHeaderLen + 2));     // source port = 53
        put16(out, udp + 2, u16(request, requestHeaderLen));     // destination = client
        put16(out, udp + 4, UDP_HEADER_LEN + payload.length);
        System.arraycopy(payload, 0, out, udp + UDP_HEADER_LEN, payload.length);
        put16(out, udp + 6, udpChecksum(out, payload.length));
        return out;
    }

    /** Standard one's-complement checksum over a byte range. */
    private static int checksum(byte[] data, int offset, int length) {
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

    /**
     * UDP checksum, which covers a pseudo-header of the IP addresses as well
     * as the datagram itself.
     */
    private static int udpChecksum(byte[] packet, int payloadLength) {
        int udpLength = UDP_HEADER_LEN + payloadLength;
        int sum = 0;
        for (int i = 12; i < 20; i += 2) {   // source and destination addresses
            sum += u16(packet, i);
        }
        sum += PROTO_UDP;
        sum += udpLength;
        for (int i = 0; i < udpLength - 1; i += 2) {
            sum += u16(packet, IPV4_HEADER_LEN + i);
        }
        if ((udpLength & 1) != 0) {
            sum += (packet[IPV4_HEADER_LEN + udpLength - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int result = (~sum) & 0xFFFF;
        // Zero means "no checksum computed", so it is transmitted as all ones.
        return result == 0 ? 0xFFFF : result;
    }
}
