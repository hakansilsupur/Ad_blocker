"""Static checks on the extension bundle.

The extension has no JS test runner, so these guard the things that silently
break it: a manifest pointing at a missing file, a rule Chrome will reject at
load time, or a generated ruleset that has drifted past the MV3 budget.
"""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXTENSION = ROOT / "extension"

VALID_RESOURCE_TYPES = {
    "main_frame", "sub_frame", "stylesheet", "script", "image", "font", "object",
    "xmlhttprequest", "ping", "csp_report", "media", "websocket", "webtransport",
    "webbundle", "other",
}


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((EXTENSION / "manifest.json").read_text())

    def test_is_manifest_v3(self):
        self.assertEqual(self.manifest["manifest_version"], 3)

    def test_every_referenced_file_exists(self):
        referenced = [
            self.manifest["background"]["service_worker"],
            self.manifest["action"]["default_popup"],
            *self.manifest["action"]["default_icon"].values(),
            *self.manifest["icons"].values(),
            *(r["path"] for r in self.manifest["declarative_net_request"]["rule_resources"]),
        ]
        for script in self.manifest["content_scripts"]:
            referenced.extend(script["js"])
        for path in referenced:
            self.assertTrue((EXTENSION / path).is_file(), f"missing {path}")

    def test_requests_only_the_permissions_it_uses(self):
        self.assertEqual(
            set(self.manifest["permissions"]),
            {"declarativeNetRequest", "declarativeNetRequestFeedback", "storage", "tabs"},
        )

    def test_off_state_icons_exist(self):
        # background.js swaps to these when blocking is disabled.
        for name in ("icon16-off.png", "icon32-off.png"):
            self.assertTrue((EXTENSION / "icons" / name).is_file(), name)


class RulesetTest(unittest.TestCase):
    def setUp(self):
        self.rules = json.loads((EXTENSION / "rules" / "ads.json").read_text())

    def test_not_empty(self):
        self.assertGreater(len(self.rules), 100)

    def test_within_static_rule_budget(self):
        from adblock.update import MAX_STATIC_RULES

        self.assertLessEqual(len(self.rules), MAX_STATIC_RULES)

    def test_ids_are_unique_and_positive(self):
        ids = [rule["id"] for rule in self.rules]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(isinstance(i, int) and i > 0 for i in ids))

    def test_ids_stay_below_the_dynamic_rule_range(self):
        # background.js allocates dynamic allow rules from 1_000_000 up.
        self.assertLess(max(rule["id"] for rule in self.rules), 1_000_000)

    def test_rule_shape(self):
        for rule in self.rules:
            self.assertEqual(rule["action"], {"type": "block"})
            self.assertIn("urlFilter", rule["condition"])
            self.assertRegex(rule["condition"]["urlFilter"], r"^\|\|[a-z0-9.-]+\^$")
            for resource_type in rule["condition"].get("resourceTypes", []):
                self.assertIn(resource_type, VALID_RESOURCE_TYPES)

    def test_does_not_declare_main_frame(self):
        # Omitting resourceTypes gives the MV3 default, which excludes
        # main_frame -- clicking through to an ad domain still loads a page.
        for rule in self.rules:
            self.assertNotIn("main_frame", rule["condition"].get("resourceTypes", []))

    def test_matches_the_generated_blocklist(self):
        from adblock.blocklist import load_default

        blocklist = load_default(ROOT / "lists")
        for rule in self.rules[:200]:
            domain = rule["condition"]["urlFilter"][2:-1]
            self.assertTrue(blocklist.is_blocked(domain), domain)


class CosmeticFilterTest(unittest.TestCase):
    """The selector lists are JS, so check them by shape rather than parsing."""

    #: Matches one quoted list entry on its own line, so apostrophes inside
    #: the surrounding comments are not mistaken for selectors.
    ENTRY_RE = re.compile(r"^\s+'([^']+)',$", re.M)

    def setUp(self):
        self.source = (EXTENSION / "src" / "cosmetic-filters.js").read_text()
        self.entries = self.ENTRY_RE.findall(self.source)

    def test_defines_the_expected_sections(self):
        for key in ("generic:", "perSite:", "adSourceFragments:"):
            self.assertIn(key, self.source)

    def test_finds_the_selector_entries(self):
        self.assertGreater(len(self.entries), 50)

    def test_selectors_are_balanced(self):
        for selector in self.entries:
            self.assertEqual(selector.count("["), selector.count("]"), selector)
            self.assertEqual(selector.count("("), selector.count(")"), selector)

    def test_no_bare_universal_or_body_selectors(self):
        # A stray '*' or 'body' rule would hide the whole page.
        for selector in self.entries:
            self.assertNotIn(selector.strip(), ("*", "body", "html", "div"))


if __name__ == "__main__":
    unittest.main()
