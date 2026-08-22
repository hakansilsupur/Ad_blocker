"""Just enough DNS wire-format handling to sinkhole and forward queries.

This deliberately does not implement a general DNS library. Queries we allow
are forwarded upstream as opaque bytes, so the only messages we build from
scratch are the sinkhole answers, and the only parsing we need is the question
name plus the TTLs that tell the cache how long to hold a reply.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

HEADER = struct.Struct("!HHHHHH")
HEADER_LEN = HEADER.size

# Record / class constants we care about.
TYPE_A = 1
TYPE_AAAA = 28
TYPE_OPT = 41
CLASS_IN = 1

# Response codes.
RCODE_NOERROR = 0
RCODE_FORMERR = 1
RCODE_SERVFAIL = 2
RCODE_NXDOMAIN = 3
RCODE_REFUSED = 5

# Flag bits within the 16-bit flags field.
FLAG_QR = 0x8000
FLAG_RD = 0x0100
FLAG_RA = 0x0080

MAX_NAME_LEN = 253
_MAX_LABEL_JUMPS = 32


class DNSFormatError(ValueError):
    """Raised when a message is too malformed to act on."""


@dataclass(frozen=True)
class Question:
    name: str
    qtype: int
    qclass: int
    end: int  # offset just past the question section


def decode_name(data: bytes, offset: int) -> tuple[str, int]:
    """Decode a (possibly compressed) name, returning it and the next offset.

    The returned offset is the position after the name *in the record being
    read* -- for a compressed name that is just past the pointer, not past the
    data it points at.
    """
    labels: list[str] = []
    jumps = 0
    end_offset = -1
    while True:
        if offset >= len(data):
            raise DNSFormatError("name runs past end of message")
        length = data[offset]
        if length == 0:
            offset += 1
            break
        if length & 0xC0 == 0xC0:  # compression pointer
            if offset + 1 >= len(data):
                raise DNSFormatError("truncated compression pointer")
            jumps += 1
            if jumps > _MAX_LABEL_JUMPS:
                raise DNSFormatError("compression pointer loop")
            pointer = ((length & 0x3F) << 8) | data[offset + 1]
            if end_offset < 0:
                end_offset = offset + 2
            if pointer >= offset:
                raise DNSFormatError("forward compression pointer")
            offset = pointer
            continue
        if length & 0xC0:
            raise DNSFormatError(f"reserved label type {length:#x}")
        offset += 1
        if offset + length > len(data):
            raise DNSFormatError("label runs past end of message")
        labels.append(data[offset : offset + length].decode("ascii", "replace"))
        offset += length
        if sum(len(label) + 1 for label in labels) > MAX_NAME_LEN:
            raise DNSFormatError("name too long")
    return ".".join(labels), (end_offset if end_offset >= 0 else offset)


def encode_name(name: str) -> bytes:
    """Encode a dotted name into wire format."""
    out = bytearray()
    for label in name.rstrip(".").split("."):
        if not label:
            continue
        encoded = label.encode("idna" if not label.isascii() else "ascii")
        if len(encoded) > 63:
            raise DNSFormatError(f"label too long: {label}")
        out.append(len(encoded))
        out += encoded
    out.append(0)
    return bytes(out)


def message_id(data: bytes) -> int:
    if len(data) < HEADER_LEN:
        raise DNSFormatError("message shorter than header")
    return struct.unpack_from("!H", data, 0)[0]


def with_id(data: bytes, new_id: int) -> bytes:
    """Return ``data`` with its transaction ID replaced (for cache replays)."""
    if len(data) < HEADER_LEN:
        raise DNSFormatError("message shorter than header")
    return struct.pack("!H", new_id) + data[2:]


def parse_question(data: bytes) -> Question:
    """Read the first question from a query."""
    if len(data) < HEADER_LEN:
        raise DNSFormatError("message shorter than header")
    _, flags, qdcount, _, _, _ = HEADER.unpack_from(data, 0)
    if flags & FLAG_QR:
        raise DNSFormatError("not a query")
    if qdcount < 1:
        raise DNSFormatError("query has no question")
    name, offset = decode_name(data, HEADER_LEN)
    if offset + 4 > len(data):
        raise DNSFormatError("truncated question")
    qtype, qclass = struct.unpack_from("!HH", data, offset)
    return Question(name=name, qtype=qtype, qclass=qclass, end=offset + 4)


def build_response(
    query: bytes,
    question: Question,
    *,
    rcode: int = RCODE_NOERROR,
    answers: bytes = b"",
    answer_count: int = 0,
) -> bytes:
    """Build a reply to ``query`` carrying pre-encoded ``answers``."""
    query_id, query_flags, _, _, _, _ = HEADER.unpack_from(query, 0)
    flags = FLAG_QR | FLAG_RA | (query_flags & FLAG_RD) | (rcode & 0x0F)
    header = HEADER.pack(query_id, flags, 1, answer_count, 0, 0)
    return header + query[HEADER_LEN : question.end] + answers


def build_sinkhole_response(
    query: bytes,
    question: Question,
    *,
    ttl: int = 60,
    nxdomain: bool = False,
) -> bytes:
    """Answer a blocked query.

    By default A/AAAA questions are answered with the unroutable addresses
    ``0.0.0.0`` / ``::`` and everything else gets an empty NOERROR. That is
    kinder than NXDOMAIN: clients fail fast on connect instead of retrying
    resolution, and apps that treat NXDOMAIN as "network is broken" keep
    working. Pass ``nxdomain=True`` if you prefer the harder answer.
    """
    if nxdomain:
        return build_response(query, question, rcode=RCODE_NXDOMAIN)

    if question.qclass == CLASS_IN and question.qtype == TYPE_A:
        rdata = b"\x00\x00\x00\x00"
    elif question.qclass == CLASS_IN and question.qtype == TYPE_AAAA:
        rdata = b"\x00" * 16
    else:
        # NODATA: the name exists, it just has nothing of this type.
        return build_response(query, question)

    answer = struct.pack(
        "!HHHIH",
        0xC00C,  # pointer to the question's name at offset 12
        question.qtype,
        question.qclass,
        ttl,
        len(rdata),
    ) + rdata
    return build_response(query, question, answers=answer, answer_count=1)


def build_servfail(query: bytes) -> bytes:
    """Best-effort SERVFAIL, used when an upstream lookup fails."""
    if len(query) < HEADER_LEN:
        return b""
    query_id, query_flags, qdcount, _, _, _ = HEADER.unpack_from(query, 0)
    flags = FLAG_QR | FLAG_RA | (query_flags & FLAG_RD) | RCODE_SERVFAIL
    header = HEADER.pack(query_id, flags, qdcount, 0, 0, 0)
    return header + query[HEADER_LEN:]


def response_ttl(data: bytes, default: int = 300, minimum: int = 30, maximum: int = 86400) -> int:
    """Smallest TTL across a response's records, clamped to a sane range."""
    try:
        _, _, qdcount, ancount, nscount, arcount = HEADER.unpack_from(data, 0)
        offset = HEADER_LEN
        for _ in range(qdcount):
            _, offset = decode_name(data, offset)
            offset += 4
        ttls: list[int] = []
        for _ in range(ancount + nscount + arcount):
            _, offset = decode_name(data, offset)
            if offset + 10 > len(data):
                break
            rtype, _, ttl, rdlength = struct.unpack_from("!HHIH", data, offset)
            offset += 10 + rdlength
            if rtype != TYPE_OPT:  # OPT reuses the TTL field for flags
                ttls.append(ttl)
        if not ttls:
            return minimum
        return max(minimum, min(maximum, min(ttls)))
    except (DNSFormatError, struct.error):
        return default


def response_rcode(data: bytes) -> int:
    if len(data) < HEADER_LEN:
        return RCODE_FORMERR
    return HEADER.unpack_from(data, 0)[1] & 0x0F


def type_name(qtype: int) -> str:
    return {
        1: "A",
        2: "NS",
        5: "CNAME",
        6: "SOA",
        12: "PTR",
        15: "MX",
        16: "TXT",
        28: "AAAA",
        33: "SRV",
        64: "SVCB",
        65: "HTTPS",
        255: "ANY",
    }.get(qtype, str(qtype))
