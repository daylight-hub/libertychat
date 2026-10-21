"""
LCS: tests for refresh_path — expire, ask, wait, and put it back if nothing came.

The restore half matters more than it looks. `Transport.clean_announce_cache`
deletes every cached announce the path table does not reference, so expiring a
path makes its announce collectable; restoring the entry without the announce
leaves it pointing at nothing, and `path_request` sets `answered = True` before
it checks the cache — so a node in that state answers with neither a reply nor a
search. These tests pin the announce bytes surviving a failed refresh.
"""

import importlib.util
import os
import shutil
import sys
import tempfile
import threading
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"

PEER = b"peer-destination"
PACKET_HASH = b"\xab\xcd\xef\x01"
ANNOUNCE_BYTES = b"the-cached-announce"


class FakeTransport:
    """A Transport with a real path table, so snapshot/restore can be exercised."""

    IDX_PT_TIMESTAMP = 0
    IDX_PT_PACKET = 6

    def __init__(self, path_appears=False):
        self.path_table = {}
        self.path_table_lock = threading.RLock()
        self.path_appears = path_appears
        self.expired = []
        self.requested = []

    def seed(self, destination_hash, timestamp=1000.0, packet_hash=PACKET_HASH):
        self.path_table[destination_hash] = [
            timestamp, b"next-hop", 2, 2000.0, [b"blob"], object(), packet_hash,
        ]

    def expire_path(self, destination_hash):
        self.expired.append(destination_hash)
        entry = self.path_table.get(destination_hash)
        if entry is None:
            return False
        # Upstream zeroes the timestamp and lets the cull remove the entry.
        entry[0] = 0
        return True

    def request_path(self, destination_hash):
        self.requested.append(destination_hash)

    def has_path(self, destination_hash):
        return self.path_appears


class Router:
    def __init__(self, own_hash=b"my-own-destination"):
        self.delivery_destinations = {own_hash: object()}
        self.announced = []

    def announce(self, destination_hash):
        self.announced.append(destination_hash)


class PathRefreshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        for name, value in (("LOG_ERROR", 3), ("LOG_DEBUG", 7), ("LOG_NOTICE", 4), ("LOG_WARNING", 2)):
            setattr(rns, name, value)
        setattr(rns, "log", lambda *a, **k: None)
        setattr(rns, "Transport", None)
        setattr(rns, "Reticulum", types.SimpleNamespace(cachepath=None))
        sys.modules["RNS"] = rns
        cls.rns = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", type("LXStamper", (), {}))
        setattr(lxmf, "LXMessage", type("LXMessage", (), {"PROPAGATED": 3, "SENT": 4, "DELIVERED": 8}))
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location("event_bridge_refresh_test", EVENT_BRIDGE_PATH)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        self.cachedir = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.cachedir, "announces"))
        self.rns.Reticulum.cachepath = self.cachedir
        self.module.PATH_RETRY_TIMEOUT_SECONDS = 0.3
        self.module.PATH_SETTLE_SECONDS = 0.0
        self.module._announce_on_failure_at.clear()
        with self.module._unconfirmed_paths_lock:
            self.module._unconfirmed_paths.clear()
        self.transport = FakeTransport()
        setattr(self.rns, "Transport", self.transport)
        self.router = Router()
        setattr(self.module, "_lxmf_router", self.router)
        self.addCleanup(shutil.rmtree, self.cachedir, True)

    def _announce_file(self, packet_hash=PACKET_HASH):
        return os.path.join(self.cachedir, "announces", packet_hash.hex())

    def _write_announce(self, packet_hash=PACKET_HASH):
        with open(self._announce_file(packet_hash), "wb") as handle:
            handle.write(ANNOUNCE_BYTES)

    # ---------------- snapshot ----------------

    def test_snapshot_captures_the_entry_and_the_cached_announce(self):
        self.transport.seed(PEER)
        self._write_announce()

        snapshot = self.module.snapshot_path_entry(PEER)

        self.assertIsNotNone(snapshot)
        self.assertEqual(ANNOUNCE_BYTES, snapshot["announce"])
        self.assertEqual(1000.0, snapshot["entry"][0])

    def test_snapshot_of_an_unknown_destination_is_none(self):
        self.assertIsNone(self.module.snapshot_path_entry(b"never-seen"))

    def test_snapshot_survives_a_missing_announce_file(self):
        self.transport.seed(PEER)
        snapshot = self.module.snapshot_path_entry(PEER)
        self.assertIsNotNone(snapshot)
        self.assertIsNone(snapshot["announce"])

    def test_an_unrecognised_entry_shape_is_not_snapshotted(self):
        self.transport.path_table[PEER] = [1000.0, b"next-hop"]
        self.assertIsNone(self.module.snapshot_path_entry(PEER))

    # ---------------- refresh: nothing answers ----------------

    def test_a_silent_refresh_puts_the_entry_back(self):
        self.transport.seed(PEER)
        self._write_announce()

        found = self.module.refresh_path(PEER)

        self.assertFalse(found)
        self.assertEqual([PEER], self.transport.expired, "must expire before asking")
        self.assertEqual([PEER], self.transport.requested)
        self.assertEqual(
            1000.0,
            self.transport.path_table[PEER][0],
            "the working route should be back, not left expired",
        )

    def test_a_silent_refresh_rewrites_a_culled_announce(self):
        # The hazard: expiring makes the announce collectable, and a cleaning
        # pass in that window deletes it. Restoring the entry alone would leave
        # it pointing at nothing.
        self.transport.seed(PEER)
        self._write_announce()
        original_refresh = self.module.refresh_path

        def cull_during_wait(*args, **kwargs):
            os.unlink(self._announce_file())
            return False
        self.transport.has_path = cull_during_wait

        found = original_refresh(PEER)

        self.assertFalse(found)
        self.assertTrue(
            os.path.isfile(self._announce_file()),
            "the cached announce must be put back with the entry",
        )
        with open(self._announce_file(), "rb") as handle:
            self.assertEqual(ANNOUNCE_BYTES, handle.read())

    def test_a_silent_refresh_announces_us_instead(self):
        self.transport.seed(PEER)

        self.module.refresh_path(PEER)

        self.assertEqual(
            [b"my-own-destination"],
            self.router.announced,
            "announcing ourselves is the only thing that crosses a hop",
        )

    def test_the_announce_can_be_suppressed_by_the_caller(self):
        self.transport.seed(PEER)
        self.module.refresh_path(PEER, announce_if_empty=False)
        self.assertEqual([], self.router.announced)

    def test_a_second_failure_does_not_announce_again_immediately(self):
        self.transport.seed(PEER)
        self.module.refresh_path(PEER)
        self.transport.seed(PEER)
        self.module.refresh_path(PEER)
        self.assertEqual(1, len(self.router.announced), "announces are floored per peer")

    # ---------------- refresh: something answers ----------------

    def test_a_refresh_that_is_answered_leaves_the_new_route_alone(self):
        self.transport.seed(PEER)
        self._write_announce()
        self.transport.path_appears = True

        found = self.module.refresh_path(PEER)

        self.assertTrue(found)
        self.assertEqual(
            0,
            self.transport.path_table[PEER][0],
            "a successful refresh must not reinstate the old entry",
        )
        self.assertEqual([], self.router.announced, "no need to announce if a route answered")

    def test_a_refresh_does_not_overwrite_a_route_that_arrived_while_waiting(self):
        self.transport.seed(PEER)
        calls = {"n": 0}

        def better_route_arrives(destination_hash):
            calls["n"] += 1
            if calls["n"] >= 2:
                self.transport.path_table[PEER] = [
                    9999.0, b"better-hop", 1, 2000.0, [b"blob"], object(), PACKET_HASH,
                ]
            return False
        self.transport.has_path = better_route_arrives

        self.module.refresh_path(PEER)

        self.assertEqual(
            9999.0,
            self.transport.path_table[PEER][0],
            "an announce that landed mid-wait wins over the snapshot",
        )

    # ---------------- the pure announce throttle ----------------

    def test_the_first_announce_for_a_peer_is_allowed(self):
        allowed, state = self.module.next_announce_on_failure(None, 500.0)
        self.assertTrue(allowed)
        self.assertEqual(500.0, state)

    def test_an_announce_inside_the_floor_is_refused(self):
        allowed, state = self.module.next_announce_on_failure(500.0, 600.0)
        self.assertFalse(allowed)
        self.assertEqual(500.0, state)

    def test_an_announce_after_the_floor_is_allowed(self):
        floor = self.module.ANNOUNCE_ON_FAILURE_MIN_INTERVAL_SECONDS
        allowed, state = self.module.next_announce_on_failure(500.0, 500.0 + floor + 1)
        self.assertTrue(allowed)
        self.assertEqual(500.0 + floor + 1, state)

    # ---------------- unconfirmed paths ----------------

    def test_a_restored_path_is_unconfirmed_until_something_vouches_for_it(self):
        self.module.mark_path_unconfirmed(PEER)
        self.assertTrue(self.module.is_path_unconfirmed(PEER))
        self.module.confirm_path(PEER)
        self.assertFalse(self.module.is_path_unconfirmed(PEER))

    def test_an_answered_refresh_confirms_the_path(self):
        self.module.mark_path_unconfirmed(PEER)
        self.transport.seed(PEER)
        self.transport.path_appears = True

        self.module.refresh_path(PEER)

        self.assertFalse(self.module.is_path_unconfirmed(PEER))


if __name__ == "__main__":
    unittest.main()
