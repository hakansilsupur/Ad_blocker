package io.github.hakansilsupur.adblock;

/**
 * Just enough DNS wire format to read a question and answer it with nothing.
 *
 * <p>Queries we allow are relayed upstream as opaque bytes, so the only
 * messages built here are the sinkhole answers.
 */
public final class DnsMessage {

    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    /** EDNS pseudo-record: its TTL field holds flags, not a lifetime. */
    public static final int TYPE_OPT = 41;
    public static final int CLASS_IN = 1;

    private static final int HEADER_LEN = 12;
    private static final int FLAG_QR = 0x8000;
    private static final int FLAG_RD = 0x0100;
    private static final int FLAG_RA = 0x0080;
    private static final int FLAG_TC = 0x0200;
    private static final int MAX_JUMPS = 16;

    /** A parsed question: the name asked about, its type, and where it ends. */
    public static final class Question {
        public final String name;
        public final int type;
        public final int dnsClass;
        /** Offset just past the question section, relative to the DNS payload. */
        public final int end;

        Question(String name, int type, int dnsClass, int end) {
            this.name = name;
            this.type = type;
            this.dnsClass = dnsClass;
            this.end = end;
        }
    }

    private DnsMessage() {
    }

    private static int u8(byte[] data, int index) {
        return data[index] & 0xFF;
    }

    private static int u16(byte[] data, int index) {
        return ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
    }

    private static void put16(byte[] data, int index, int value) {
        data[index] = (byte) ((value >> 8) & 0xFF);
        data[index + 1] = (byte) (value & 0xFF);
    }

    /**
     * Read the first question from a query.
     *
     * @return the question, or null if the message is not a parseable query
     */
    public static Question parseQuestion(byte[] payload, int offset, int length) {
        if (length < HEADER_LEN + 5) {
            return null;
        }
        int flags = u16(payload, offset + 2);
        if ((flags & FLAG_QR) != 0) {
            return null; // a response, not a query
        }
        if (u16(payload, offset + 4) < 1) {
            return null; // no question section
        }

        StringBuilder name = new StringBuilder(64);
        int cursor = offset + HEADER_LEN;
        int end = offset + length;
        int jumps = 0;
        int afterName = -1;

        while (true) {
            if (cursor >= end) {
                return null;
            }
            int len = u8(payload, cursor);
            if (len == 0) {
                cursor++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                if (cursor + 1 >= end || ++jumps > MAX_JUMPS) {
                    return null;
                }
                int pointer = offset + (((len & 0x3F) << 8) | u8(payload, cursor + 1));
                if (afterName < 0) {
                    afterName = cursor + 2;
                }
                if (pointer >= cursor) {
                    return null; // only backward pointers are legal
                }
                cursor = pointer;
                continue;
            }
            if ((len & 0xC0) != 0) {
                return null;
            }
            cursor++;
            if (cursor + len > end) {
                return null;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            for (int i = 0; i < len; i++) {
                name.append((char) (payload[cursor + i] & 0xFF));
            }
            cursor += len;
            if (name.length() > 253) {
                return null;
            }
        }

        int questionEnd = (afterName >= 0 ? afterName : cursor) + 4;
        if (questionEnd > end) {
            return null;
        }
        int type = u16(payload, (afterName >= 0 ? afterName : cursor));
        int dnsClass = u16(payload, (afterName >= 0 ? afterName : cursor) + 2);
        return new Question(name.toString(), type, dnsClass, questionEnd - offset);
    }

    /**
     * Build the answer for a blocked name.
     *
     * <p>A and AAAA questions get the unroutable addresses {@code 0.0.0.0} and
     * {@code ::}; anything else gets an empty NOERROR. That is gentler than
     * NXDOMAIN -- apps fail fast on connect instead of deciding the whole
     * network is broken.
     */
    public static byte[] buildSinkholeResponse(
            byte[] query, int offset, int length, Question question, int ttlSeconds) {
        int questionBytes = question.end - HEADER_LEN;
        boolean hasAnswer = question.dnsClass == CLASS_IN
                && (question.type == TYPE_A || question.type == TYPE_AAAA);
        int rdataLength = question.type == TYPE_A ? 4 : 16;
        int answerBytes = hasAnswer ? 12 + rdataLength : 0;

        byte[] response = new byte[HEADER_LEN + questionBytes + answerBytes];
        // Transaction ID, echoed so the client can match the reply.
        response[0] = query[offset];
        response[1] = query[offset + 1];
        int queryFlags = u16(query, offset + 2);
        put16(response, 2, FLAG_QR | FLAG_RA | (queryFlags & FLAG_RD));
        put16(response, 4, 1);                       // QDCOUNT
        put16(response, 6, hasAnswer ? 1 : 0);       // ANCOUNT
        put16(response, 8, 0);                       // NSCOUNT
        put16(response, 10, 0);                      // ARCOUNT
        System.arraycopy(query, offset + HEADER_LEN, response, HEADER_LEN, questionBytes);

        if (hasAnswer) {
            int at = HEADER_LEN + questionBytes;
            put16(response, at, 0xC00C);             // pointer to the question's name
            put16(response, at + 2, question.type);
            put16(response, at + 4, question.dnsClass);
            response[at + 6] = (byte) ((ttlSeconds >> 24) & 0xFF);
            response[at + 7] = (byte) ((ttlSeconds >> 16) & 0xFF);
            response[at + 8] = (byte) ((ttlSeconds >> 8) & 0xFF);
            response[at + 9] = (byte) (ttlSeconds & 0xFF);
            put16(response, at + 10, rdataLength);
            // The record data is all zeroes: 0.0.0.0 or ::
        }
        return response;
    }

    /**
     * A header-only reply with the truncation bit set, telling the client to
     * retry over TCP. Used when a response will not fit the tunnel's MTU.
     */
    public static byte[] buildTruncatedResponse(byte[] query, int offset, Question question) {
        int questionBytes = question.end - HEADER_LEN;
        byte[] response = new byte[HEADER_LEN + questionBytes];
        response[0] = query[offset];
        response[1] = query[offset + 1];
        int queryFlags = u16(query, offset + 2);
        put16(response, 2, FLAG_QR | FLAG_RA | FLAG_TC | (queryFlags & FLAG_RD));
        put16(response, 4, 1);
        System.arraycopy(query, offset + HEADER_LEN, response, HEADER_LEN, questionBytes);
        return response;
    }

    /** The RCODE of a response, or -1 if it is too short to have one. */
    public static int rcode(byte[] data, int offset, int length) {
        if (length < HEADER_LEN) {
            return -1;
        }
        return u16(data, offset + 2) & 0x0F;
    }

    /** Copy a transaction ID onto a response, so a cached answer matches. */
    public static void setId(byte[] response, byte[] query, int queryOffset) {
        if (response.length >= 2) {
            response[0] = query[queryOffset];
            response[1] = query[queryOffset + 1];
        }
    }

    /**
     * Step over a name, following a compression pointer if there is one.
     *
     * @return the offset just past the name, or -1 if it runs off the end
     */
    private static int skipName(byte[] data, int offset, int end) {
        while (true) {
            if (offset >= end) {
                return -1;
            }
            int len = data[offset] & 0xFF;
            if (len == 0) {
                return offset + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return offset + 2 <= end ? offset + 2 : -1;
            }
            if ((len & 0xC0) != 0) {
                return -1;
            }
            offset += 1 + len;
        }
    }

    /**
     * The smallest TTL across a response's records, which is how long the
     * whole answer may be cached.
     *
     * @return the TTL in seconds, or {@code fallback} if it cannot be read
     */
    public static int minTtlSeconds(byte[] data, int offset, int length, int fallback) {
        if (length < HEADER_LEN) {
            return fallback;
        }
        int end = offset + length;
        int qdcount = u16(data, offset + 4);
        int records = u16(data, offset + 6) + u16(data, offset + 8) + u16(data, offset + 10);
        int cursor = offset + HEADER_LEN;

        for (int i = 0; i < qdcount; i++) {
            cursor = skipName(data, cursor, end);
            if (cursor < 0 || cursor + 4 > end) {
                return fallback;
            }
            cursor += 4;
        }

        int smallest = Integer.MAX_VALUE;
        for (int i = 0; i < records; i++) {
            cursor = skipName(data, cursor, end);
            if (cursor < 0 || cursor + 10 > end) {
                break;
            }
            int type = u16(data, cursor);
            long ttl = ((long) u16(data, cursor + 4) << 16) | u16(data, cursor + 6);
            int rdlength = u16(data, cursor + 8);
            cursor += 10 + rdlength;
            // OPT reuses the TTL field for EDNS flags, so it says nothing here.
            if (type != TYPE_OPT && ttl < smallest) {
                smallest = (int) Math.min(ttl, Integer.MAX_VALUE);
            }
        }
        return smallest == Integer.MAX_VALUE ? fallback : smallest;
    }

    public static String typeName(int type) {
        switch (type) {
            case 1: return "A";
            case 2: return "NS";
            case 5: return "CNAME";
            case 6: return "SOA";
            case 12: return "PTR";
            case 15: return "MX";
            case 16: return "TXT";
            case 28: return "AAAA";
            case 33: return "SRV";
            case 64: return "SVCB";
            case 65: return "HTTPS";
            default: return Integer.toString(type);
        }
    }
}
