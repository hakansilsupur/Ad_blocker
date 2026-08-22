import unittest

from adblock.cache import TTLCache


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class TTLCacheTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.cache = TTLCache(max_entries=3, clock=self.clock)

    def test_stores_and_returns(self):
        self.cache.set("a", b"payload", ttl=60)
        self.assertEqual(self.cache.get("a"), b"payload")
        self.assertEqual(self.cache.hits, 1)

    def test_expires_on_ttl(self):
        self.cache.set("a", b"payload", ttl=60)
        self.clock.advance(61)
        self.assertIsNone(self.cache.get("a"))
        self.assertEqual(len(self.cache), 0)

    def test_zero_ttl_is_not_stored(self):
        self.cache.set("a", b"payload", ttl=0)
        self.assertIsNone(self.cache.get("a"))

    def test_evicts_least_recently_used(self):
        for key in "abc":
            self.cache.set(key, key.encode(), ttl=60)
        self.cache.get("a")  # "b" is now the least recently used
        self.cache.set("d", b"d", ttl=60)
        self.assertEqual(len(self.cache), 3)
        self.assertIsNone(self.cache.get("b"))
        self.assertEqual(self.cache.get("a"), b"a")

    def test_purge_expired(self):
        self.cache.set("a", b"a", ttl=10)
        self.cache.set("b", b"b", ttl=100)
        self.clock.advance(50)
        self.assertEqual(self.cache.purge_expired(), 1)
        self.assertEqual(len(self.cache), 1)

    def test_miss_counter(self):
        self.cache.get("nothing")
        self.assertEqual(self.cache.misses, 1)


if __name__ == "__main__":
    unittest.main()
