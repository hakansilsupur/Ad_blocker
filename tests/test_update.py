"""Tests for the list compiler, with the network stubbed out."""

import contextlib
import io
import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

from adblock import update
from adblock.blocklist import Blocklist


@contextlib.contextmanager
def quiet():
    """Swallow the progress output the compiler prints as it works."""
    with contextlib.redirect_stdout(io.StringIO()):
        yield


SOURCE_A = "0.0.0.0 ads.example.com\n0.0.0.0 shared.example.com\n"
SOURCE_B = "||shared.example.com^\n||tracker.example.net^\n"


class BuildTest(unittest.TestCase):
    def build(self, **kwargs):
        pages = {"a": SOURCE_A, "b": SOURCE_B}
        with mock.patch.object(update, "fetch", side_effect=lambda url, **_: pages[url]), quiet():
            return update.build({"a": "a", "b": "b"}, **kwargs)

    def test_merges_every_source(self):
        blocklist, _ = self.build()
        for domain in ["ads.example.com", "shared.example.com", "tracker.example.net"]:
            self.assertTrue(blocklist.is_blocked(domain), domain)

    def test_consensus_counts_each_source_that_lists_a_domain(self):
        _, consensus = self.build()
        self.assertEqual(consensus["shared.example.com"], 2)
        self.assertEqual(consensus["ads.example.com"], 1)

    def test_a_failing_source_does_not_stop_the_build(self):
        def flaky(url, **_):
            if url == "a":
                raise OSError("boom")
            return SOURCE_B

        with mock.patch.object(update, "fetch", side_effect=flaky), quiet():
            blocklist, _ = update.build({"a": "a", "b": "b"})
        self.assertTrue(blocklist.is_blocked("tracker.example.net"))
        self.assertFalse(blocklist.is_blocked("ads.example.com"))

    def test_offline_uses_only_the_seed(self):
        with tempfile.TemporaryDirectory() as tmp:
            seed = Path(tmp) / "seed.txt"
            seed.write_text("seeded.example.com\n")
            with mock.patch.object(
                update, "fetch", side_effect=AssertionError("no network")
            ), quiet():
                blocklist, consensus = update.build(seed_files=[seed], offline=True)
        self.assertTrue(blocklist.is_blocked("seeded.example.com"))
        self.assertEqual(len(blocklist), 1)
        self.assertGreater(consensus["seeded.example.com"], 1)

    def test_seed_outranks_the_downloaded_lists(self):
        with tempfile.TemporaryDirectory() as tmp:
            seed = Path(tmp) / "seed.txt"
            seed.write_text("seeded.example.com\n")
            pages = {"a": SOURCE_A, "b": SOURCE_B}
            with mock.patch.object(
                update, "fetch", side_effect=lambda url, **_: pages[url]
            ), quiet():
                _, consensus = update.build({"a": "a", "b": "b"}, seed_files=[seed])
        self.assertGreater(consensus["seeded.example.com"], consensus["shared.example.com"])


class ExtensionRulesTest(unittest.TestCase):
    def test_rule_shape(self):
        blocklist = Blocklist()
        blocklist.block("ads.example.com")
        rules = update.build_extension_rules(blocklist)
        self.assertEqual(len(rules), 1)
        self.assertEqual(rules[0]["id"], 1)
        self.assertEqual(rules[0]["action"], {"type": "block"})
        self.assertEqual(rules[0]["condition"]["urlFilter"], "||ads.example.com^")

    def test_ids_are_sequential_and_unique(self):
        blocklist = Blocklist()
        for i in range(50):
            blocklist.block(f"ads{i}.example.com")
        rules = update.build_extension_rules(blocklist)
        self.assertEqual([r["id"] for r in rules], list(range(1, 51)))

    def test_limit_keeps_the_highest_consensus_domains(self):
        blocklist = Blocklist()
        for domain in ["rare.example.com", "common.example.com", "medium.example.com"]:
            blocklist.block(domain)
        consensus = Counter({"common.example.com": 9, "medium.example.com": 4})
        rules = update.build_extension_rules(blocklist, consensus, limit=2)
        kept = {rule["condition"]["urlFilter"][2:-1] for rule in rules}
        self.assertEqual(kept, {"common.example.com", "medium.example.com"})

    def test_output_is_deterministic(self):
        # CI rebuilds the committed ruleset and diffs it, so ordering must be
        # stable across runs even though the source is a set.
        blocklist = Blocklist()
        for i in range(200):
            blocklist.block(f"ads{i}.example.com")
        first = update.build_extension_rules(blocklist)
        second = update.build_extension_rules(Blocklist().merge(blocklist))
        self.assertEqual(first, second)


class RunTest(unittest.TestCase):
    def test_writes_both_outputs_and_honours_the_allowlist(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "lists").mkdir()
            (root / "lists" / "seed.txt").write_text(
                "ads.example.com\nkeepme.example.com\nsub.ads.example.com\n"
            )
            (root / "lists" / "allowlist.txt").write_text("# note\nkeepme.example.com\n")

            with quiet():
                summary = update.run(root, offline=True)

            written = (root / "lists" / "blocklist.txt").read_text()
            rules = json.loads((root / "extension" / "rules" / "ads.json").read_text())

        self.assertIn("ads.example.com", written)
        self.assertEqual(summary["allowlist_entries"], 1)
        # The allowlisted domain is still on the blocklist file, but the
        # allowlist wins at match time; the redundant subdomain is dropped.
        self.assertNotIn("sub.ads.example.com", written.split())
        self.assertEqual(
            {rule["condition"]["urlFilter"] for rule in rules},
            {"||ads.example.com^", "||keepme.example.com^"},
        )


if __name__ == "__main__":
    unittest.main()
