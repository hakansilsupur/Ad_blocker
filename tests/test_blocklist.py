import unittest

from adblock.blocklist import Blocklist, is_valid_domain, normalize_domain, parent_domains


class NormalizeTest(unittest.TestCase):
    def test_lowercases_and_strips_root_dot(self):
        self.assertEqual(normalize_domain("  ADS.Example.COM. "), "ads.example.com")

    def test_strips_port(self):
        self.assertEqual(normalize_domain("ads.example.com:8080"), "ads.example.com")

    def test_validity(self):
        self.assertTrue(is_valid_domain("ads.example.com"))
        self.assertFalse(is_valid_domain("localhost"))  # no dot
        self.assertFalse(is_valid_domain(""))
        self.assertFalse(is_valid_domain("-bad.example.com"))
        self.assertFalse(is_valid_domain("a" * 300 + ".com"))

    def test_parent_domains(self):
        self.assertEqual(
            list(parent_domains("a.b.example.com")),
            ["a.b.example.com", "b.example.com", "example.com", "com"],
        )


class MatchingTest(unittest.TestCase):
    def setUp(self):
        self.blocklist = Blocklist()
        self.blocklist.block("doubleclick.net")

    def test_blocks_exact_and_subdomains(self):
        self.assertTrue(self.blocklist.is_blocked("doubleclick.net"))
        self.assertTrue(self.blocklist.is_blocked("stats.g.doubleclick.net"))

    def test_does_not_block_unrelated_or_suffix_lookalikes(self):
        self.assertFalse(self.blocklist.is_blocked("example.com"))
        self.assertFalse(self.blocklist.is_blocked("notdoubleclick.net"))

    def test_case_and_trailing_dot_insensitive(self):
        self.assertTrue(self.blocklist.is_blocked("STATS.DoubleClick.NET."))

    def test_allow_rule_beats_block(self):
        self.blocklist.allow("safe.doubleclick.net")
        self.assertFalse(self.blocklist.is_blocked("safe.doubleclick.net"))
        self.assertFalse(self.blocklist.is_blocked("deeper.safe.doubleclick.net"))
        self.assertTrue(self.blocklist.is_blocked("ads.doubleclick.net"))

    def test_nearest_rule_wins(self):
        # An allow on a child does not unblock its blocked parent's siblings.
        self.blocklist.allow("doubleclick.net")
        self.blocklist.block("ads.doubleclick.net")
        self.assertTrue(self.blocklist.is_blocked("ads.doubleclick.net"))
        self.assertFalse(self.blocklist.is_blocked("other.doubleclick.net"))

    def test_never_blocks_localhost(self):
        self.assertFalse(self.blocklist.block("localhost"))
        self.assertFalse(self.blocklist.is_blocked("localhost"))


class ParsingTest(unittest.TestCase):
    def parse(self, text):
        blocklist = Blocklist()
        blocklist.load_text(text)
        return blocklist

    def test_hosts_format(self):
        blocklist = self.parse("0.0.0.0 ads.example.com\n127.0.0.1 track.example.com\n")
        self.assertTrue(blocklist.is_blocked("ads.example.com"))
        self.assertTrue(blocklist.is_blocked("track.example.com"))

    def test_hosts_line_with_several_names(self):
        blocklist = self.parse("0.0.0.0 a.example.com b.example.com\n")
        self.assertTrue(blocklist.is_blocked("a.example.com"))
        self.assertTrue(blocklist.is_blocked("b.example.com"))

    def test_real_hosts_entries_are_not_blocked(self):
        # A hosts file mapping a name to a real address is not an ad rule.
        blocklist = self.parse("192.168.1.10 nas.local.example.com\n")
        self.assertFalse(blocklist.is_blocked("nas.local.example.com"))

    def test_plain_domain_list(self):
        blocklist = self.parse("ads.example.com\n# comment\n\n! also comment\n")
        self.assertTrue(blocklist.is_blocked("ads.example.com"))
        self.assertEqual(len(blocklist), 1)

    def test_wildcard_prefix(self):
        # "*.example.com" is how some lists spell what we already do.
        blocklist = self.parse("*.ads.example.com\n")
        self.assertTrue(blocklist.is_blocked("banner.ads.example.com"))
        self.assertTrue(blocklist.is_blocked("ads.example.com"))

    def test_trailing_comment_is_stripped(self):
        blocklist = self.parse("0.0.0.0 ads.example.com # an ad server\n")
        self.assertTrue(blocklist.is_blocked("ads.example.com"))

    def test_adblock_plus_rules(self):
        blocklist = self.parse("||ads.example.com^\n@@||good.example.com^\n")
        self.assertTrue(blocklist.is_blocked("ads.example.com"))
        self.assertTrue(blocklist.is_allowed("good.example.com"))

    def test_abp_rules_with_domain_wide_options(self):
        blocklist = self.parse("||ads.example.com^$third-party\n||b.example.com^$all\n")
        self.assertTrue(blocklist.is_blocked("ads.example.com"))
        self.assertTrue(blocklist.is_blocked("b.example.com"))

    def test_abp_rules_we_cannot_express_are_skipped(self):
        # These are scoped to a resource type or a site, so blocking the whole
        # domain at the DNS layer would over-block.
        blocklist = self.parse(
            "||cdn.example.com^$image\n"
            "||cdn.example.com^$domain=other.com\n"
            "||example.com/ads/banner.js\n"
            "example.com##.ad-slot\n"
        )
        self.assertEqual(len(blocklist), 0)

    def test_counts_are_reported(self):
        blocklist = self.parse("0.0.0.0 ads.example.com\n# note\n@@||ok.example.com^\n")
        self.assertEqual(blocklist.stats.blocked, 1)
        self.assertEqual(blocklist.stats.allowed, 1)
        self.assertEqual(blocklist.stats.skipped, 1)
        self.assertEqual(blocklist.stats.lines, 3)


class CollapseTest(unittest.TestCase):
    """Redundant children cost a slot in the extension's rule budget."""

    def setUp(self):
        self.blocklist = Blocklist()

    def test_drops_children_of_a_blocked_parent(self):
        for domain in ["example.com", "ads.example.com", "a.b.example.com", "other.net"]:
            self.blocklist.block(domain)
        self.assertEqual(self.blocklist.collapse_redundant(), 2)
        self.assertEqual(self.blocklist.blocked_domains, {"example.com", "other.net"})

    def test_matching_is_unchanged_by_collapsing(self):
        self.blocklist.block("example.com")
        self.blocklist.block("ads.example.com")
        self.blocklist.collapse_redundant()
        self.assertTrue(self.blocklist.is_blocked("ads.example.com"))
        self.assertTrue(self.blocklist.is_blocked("deep.ads.example.com"))

    def test_keeps_a_child_shielded_by_an_allow_rule(self):
        # example.com blocked, safe.example.com allowed, ads.safe.example.com
        # blocked again -- the innermost rule is the only thing blocking it.
        self.blocklist.block("example.com")
        self.blocklist.allow("safe.example.com")
        self.blocklist.block("ads.safe.example.com")
        self.assertEqual(self.blocklist.collapse_redundant(), 0)
        self.assertTrue(self.blocklist.is_blocked("ads.safe.example.com"))
        self.assertFalse(self.blocklist.is_blocked("other.safe.example.com"))

    def test_nothing_to_collapse(self):
        self.blocklist.block("a.com")
        self.blocklist.block("b.com")
        self.assertEqual(self.blocklist.collapse_redundant(), 0)


class MergeTest(unittest.TestCase):
    def test_merges_rules_and_counts(self):
        first = Blocklist()
        first.load_text("ads.example.com\n")
        second = Blocklist()
        second.load_text("track.example.com\n@@||ok.example.com^\n")

        first.merge(second)
        self.assertTrue(first.is_blocked("ads.example.com"))
        self.assertTrue(first.is_blocked("track.example.com"))
        self.assertTrue(first.is_allowed("ok.example.com"))
        self.assertEqual(first.stats.blocked, 2)
        self.assertEqual(first.stats.allowed, 1)


class SeedListTest(unittest.TestCase):
    """The committed seed list must stay loadable and sane."""

    def setUp(self):
        from pathlib import Path

        self.blocklist = Blocklist()
        self.blocklist.load_file(Path(__file__).resolve().parent.parent / "lists" / "seed.txt")

    def test_covers_web_and_app_advertising(self):
        for domain in [
            "pagead2.googlesyndication.com",
            "stats.g.doubleclick.net",
            "ads.applovin.com",
            "auction.unityads.unity3d.com",
            "api.vungle.com",
            "live.chartboost.com",
        ]:
            self.assertTrue(self.blocklist.is_blocked(domain), domain)

    def test_leaves_essential_domains_alone(self):
        for domain in [
            "www.google.com",
            "fonts.googleapis.com",
            "gstatic.com",
            "github.com",
            "cloudflare.com",
            "wikipedia.org",
            "apple.com",
            "amazon.com",
        ]:
            self.assertFalse(self.blocklist.is_blocked(domain), domain)


if __name__ == "__main__":
    unittest.main()
