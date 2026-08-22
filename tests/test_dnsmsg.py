import struct
import unittest

from adblock import dnsmsg


def make_query(name: str, qtype: int = dnsmsg.TYPE_A, query_id: int = 0x1234) -> bytes:
    header = dnsmsg.HEADER.pack(query_id, dnsmsg.FLAG_RD, 1, 0, 0, 0)
    return header + dnsmsg.encode_name(name) + struct.pack("!HH", qtype, dnsmsg.CLASS_IN)


class NameCodecTest(unittest.TestCase):
    def test_round_trip(self):
        wire = dnsmsg.encode_name("ads.example.com")
        name, offset = dnsmsg.decode_name(wire, 0)
        self.assertEqual(name, "ads.example.com")
        self.assertEqual(offset, len(wire))

    def test_compression_pointer(self):
        # "example.com" at offset 2, then a name that points back at it.
        base = b"\x00\x00" + dnsmsg.encode_name("example.com")
        message = base + b"\x03ads" + b"\xc0\x02"
        name, offset = dnsmsg.decode_name(message, len(base))
        self.assertEqual(name, "ads.example.com")
        self.assertEqual(offset, len(message))

    def test_rejects_pointer_loop(self):
        # A pointer to itself would otherwise spin forever.
        with self.assertRaises(dnsmsg.DNSFormatError):
            dnsmsg.decode_name(b"\xc0\x00", 0)

    def test_rejects_truncated_name(self):
        with self.assertRaises(dnsmsg.DNSFormatError):
            dnsmsg.decode_name(b"\x05abc", 0)


class QuestionTest(unittest.TestCase):
    def test_parses_question(self):
        question = dnsmsg.parse_question(make_query("ads.example.com"))
        self.assertEqual(question.name, "ads.example.com")
        self.assertEqual(question.qtype, dnsmsg.TYPE_A)
        self.assertEqual(question.qclass, dnsmsg.CLASS_IN)

    def test_rejects_a_response(self):
        query = make_query("example.com")
        response = dnsmsg.build_response(query, dnsmsg.parse_question(query))
        with self.assertRaises(dnsmsg.DNSFormatError):
            dnsmsg.parse_question(response)

    def test_rejects_short_message(self):
        with self.assertRaises(dnsmsg.DNSFormatError):
            dnsmsg.parse_question(b"\x00\x01")


class SinkholeTest(unittest.TestCase):
    def sinkhole(self, name, qtype=dnsmsg.TYPE_A, **kwargs):
        query = make_query(name, qtype)
        question = dnsmsg.parse_question(query)
        return dnsmsg.build_sinkhole_response(query, question, **kwargs)

    def test_a_record_answers_unroutable_address(self):
        response = self.sinkhole("ads.example.com")
        _, flags, qdcount, ancount, _, _ = dnsmsg.HEADER.unpack_from(response, 0)
        self.assertTrue(flags & dnsmsg.FLAG_QR)
        self.assertEqual(flags & 0x0F, dnsmsg.RCODE_NOERROR)
        self.assertEqual((qdcount, ancount), (1, 1))
        self.assertTrue(response.endswith(b"\x00\x00\x00\x00"))

    def test_keeps_the_query_id(self):
        response = self.sinkhole("ads.example.com")
        self.assertEqual(dnsmsg.message_id(response), 0x1234)

    def test_aaaa_answers_all_zero_address(self):
        response = self.sinkhole("ads.example.com", dnsmsg.TYPE_AAAA)
        self.assertEqual(dnsmsg.HEADER.unpack_from(response, 0)[3], 1)
        self.assertTrue(response.endswith(b"\x00" * 16))

    def test_other_types_get_empty_noerror(self):
        response = self.sinkhole("ads.example.com", qtype=16)  # TXT
        _, flags, _, ancount, _, _ = dnsmsg.HEADER.unpack_from(response, 0)
        self.assertEqual(flags & 0x0F, dnsmsg.RCODE_NOERROR)
        self.assertEqual(ancount, 0)

    def test_nxdomain_mode(self):
        response = self.sinkhole("ads.example.com", nxdomain=True)
        self.assertEqual(dnsmsg.response_rcode(response), dnsmsg.RCODE_NXDOMAIN)


class ResponseHelpersTest(unittest.TestCase):
    def build_answer(self, ttl: int) -> bytes:
        query = make_query("example.com")
        question = dnsmsg.parse_question(query)
        rdata = bytes([93, 184, 216, 34])
        answer = struct.pack(
            "!HHHIH", 0xC00C, dnsmsg.TYPE_A, dnsmsg.CLASS_IN, ttl, len(rdata)
        ) + rdata
        return dnsmsg.build_response(query, question, answers=answer, answer_count=1)

    def test_reads_ttl(self):
        self.assertEqual(dnsmsg.response_ttl(self.build_answer(300)), 300)

    def test_clamps_ttl(self):
        self.assertEqual(dnsmsg.response_ttl(self.build_answer(1)), 30)
        self.assertEqual(dnsmsg.response_ttl(self.build_answer(999999)), 86400)

    def test_ttl_of_a_response_with_no_records_is_the_floor(self):
        self.assertEqual(dnsmsg.response_ttl(b"\x00" * 12, minimum=30), 30)

    def test_ttl_of_an_unparseable_response_falls_back_to_default(self):
        # Claims one answer, then a truncated name where the record should be.
        broken = dnsmsg.HEADER.pack(1, dnsmsg.FLAG_QR, 0, 1, 0, 0) + b"\x09trunc"
        self.assertEqual(dnsmsg.response_ttl(broken, default=42), 42)

    def test_with_id_replaces_only_the_id(self):
        original = self.build_answer(300)
        changed = dnsmsg.with_id(original, 0xBEEF)
        self.assertEqual(dnsmsg.message_id(changed), 0xBEEF)
        self.assertEqual(changed[2:], original[2:])

    def test_servfail(self):
        response = dnsmsg.build_servfail(make_query("example.com"))
        self.assertEqual(dnsmsg.response_rcode(response), dnsmsg.RCODE_SERVFAIL)


if __name__ == "__main__":
    unittest.main()
