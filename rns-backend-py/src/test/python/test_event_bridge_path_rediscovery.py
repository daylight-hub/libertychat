"""
LCS: tests for the delivery ladder — direct, rediscover, direct again,
propagation, failure.

The ordering assertions are the point. Reversing rediscovery and propagation is
invisible in ordinary testing: the message still arrives, just via a propagation
node that was never needed, with the peer reachable all along by a route nobody
asked for.
"""

import importlib.util
import sys
import threading
import time
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"

PEER_HASH = b"peer-destination"
NODE_HASH = b"relay"


class Callback:
    def __init__(self, events, name):
        self.events = events
        self.name = name

    def onEvent(self, payload):
        self.events.append((self.name, payload.get("hash")))


class Destination:
    def __init__(self, hash_bytes=PEER_HASH):
        self.hash = hash_bytes


class Message:
    """An LXMessage stub that, unlike the fallback-ordering one, has a destination."""

    def __init__(self, desired_method=2):
        self.hash = b"message-hash"
        self.method = desired_method
        self.desired_method = desired_method
        self.state = 1
        self.try_propagation_on_fail = False
        self.delivery_attempts = 4
        self.packed = b"packed"
        self.propagation_packed = b"propagated"
        self.propagation_stamp = b"stamp"
        self.defer_propagation_stamp = False
        self.delivery_callback = None
        self.failed_callback = None

    def get_destination(self):
        return Destination()

    def register_delivery_callback(self, callback):
        self.delivery_callback = callback

    def register_failed_callback(self, callback):
        self.failed_callback = callback


class Router:
    outbound_propagation_node = b"relay"

    def __init__(self, events):
        self.events = events
        self.submitted = threading.Event()
        self.submits = 0

    def handle_outbound(self, message):
        self.submits += 1
        message.method = message.desired_method
        message.state = 4
        self.events.append(("submit", message.desired_method))
        self.submitted.set()


class SelectiveTransport:
    """Like FakeTransport, but only some destinations are reachable.

    Rung 3 needs the peer unreachable and the node's answer under test, which a
    single `path_appears` flag cannot express.
    """

    def __init__(self, events, reachable=()):
        self.events = events
        self.reachable = set(reachable)

    def expire_path(self, destination_hash):
        self.events.append(("expire_path", destination_hash))
        return True

    def request_path(self, destination_hash):
        self.events.append(("request_path", destination_hash))
        return None

    def has_path(self, destination_hash):
        return destination_hash in self.reachable


class FakeTransport:
    """Records what the worker asked of RNS, and controls whether a route lands."""

    def __init__(self, events, path_appears):
        self.events = events
        self.path_appears = path_appears

    def expire_path(self, destination_hash):
        self.events.append(("expire_path", destination_hash))
        return True

    def request_path(self, destination_hash):
        self.events.append(("request_path", destination_hash))
        return None

    def has_path(self, destination_hash):
        return self.path_appears


class PathRediscoveryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        for level, value in (("LOG_ERROR", 3), ("LOG_DEBUG", 7), ("LOG_NOTICE", 4), ("LOG_WARNING", 2)):
            setattr(rns, level, value)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(rns, "Transport", None)
        sys.modules["RNS"] = rns
        cls.rns = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", type("LXStamper", (), {}))
        setattr(
            lxmf,
            "LXMessage",
            type(
                "LXMessage",
                (),
                {"OPPORTUNISTIC": 1, "DIRECT": 2, "PROPAGATED": 3, "SENT": 4, "DELIVERED": 8},
            ),
        )
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location("event_bridge_rediscovery_test", EVENT_BRIDGE_PATH)
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        # Module-level tables persist between tests; each one starts clean.
        self.module._path_retry_pending.clear()
        self.module._path_retry_inflight.clear()
        self.module._path_retry_attempts.clear()
        # Keep the waits short enough to run in a unit test.
        self.module.PATH_RETRY_TIMEOUT_SECONDS = 0.4
        self.events = []

    def _attach(self, message, router, try_propagation=True):
        setattr(self.module, "_lxmf_router", router)
        self.module.attach_lxmessage_callbacks(
            message,
            Callback(self.events, "delivered"),
            Callback(self.events, "failed"),
            Callback(self.events, "retrying"),
            try_propagation_on_fail=try_propagation,
        )

    def _wait_until(self, predicate, timeout=3.0, message="condition not reached"):
        """Rungs resolve on worker threads, so waiting beats assuming."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate():
                return
            time.sleep(0.01)
        self.fail("{} (events: {})".format(message, [name for name, _ in self.events]))

    def _asked_about(self, destination_hash):
        return [
            name
            for name, value in self.events
            if name in ("expire_path", "request_path") and value == destination_hash
        ]

    def _reach_propagated_failure(self, message, router):
        """Drive the ladder to a failed propagated send, which is rung 3's input."""
        message.failed_callback(message)
        self._wait_until(
            lambda: router.submits >= 1, message="the message never reached propagation",
        )
        self.assertEqual(
            3, message.desired_method, "precondition: should be propagated by now",
        )
        # The router stub does not fail on its own; LXMF would call this back.
        message.failed_callback(message)

    # ---------------- the pure throttle decision ----------------

    def test_first_attempt_for_a_peer_is_allowed(self):
        allowed, state = self.module.next_path_retry_attempt(None, 1000.0)
        self.assertTrue(allowed)
        self.assertEqual(1, state["count"])

    def test_a_second_attempt_inside_the_minimum_interval_is_refused(self):
        # A retry can fail and call straight back in; this is what stops a tight
        # loop putting a broadcast on the air every few seconds.
        _, state = self.module.next_path_retry_attempt(None, 1000.0)
        allowed, unchanged = self.module.next_path_retry_attempt(state, 1030.0)
        self.assertFalse(allowed)
        self.assertEqual(state, unchanged)

    def test_the_interval_elapsing_allows_another_attempt(self):
        _, state = self.module.next_path_retry_attempt(None, 1000.0)
        allowed, second = self.module.next_path_retry_attempt(state, 1061.0)
        self.assertTrue(allowed)
        self.assertEqual(2, second["count"])

    def test_the_attempt_cap_is_enforced(self):
        now = 1000.0
        state = None
        for _ in range(self.module.PATH_RETRY_MAX_ATTEMPTS):
            allowed, state = self.module.next_path_retry_attempt(state, now)
            self.assertTrue(allowed)
            now += self.module.PATH_RETRY_MIN_INTERVAL_SECONDS + 1
        allowed, _ = self.module.next_path_retry_attempt(state, now)
        self.assertFalse(allowed, "should stop asking after the cap")

    def test_a_quiet_period_resets_the_budget(self):
        now = 1000.0
        state = None
        for _ in range(self.module.PATH_RETRY_MAX_ATTEMPTS):
            _, state = self.module.next_path_retry_attempt(state, now)
            now += self.module.PATH_RETRY_MIN_INTERVAL_SECONDS + 1
        allowed, reset = self.module.next_path_retry_attempt(
            state, now + self.module.PATH_RETRY_BUDGET_RESET_SECONDS,
        )
        self.assertTrue(allowed)
        self.assertEqual(1, reset["count"], "budget should start over, not continue")

    # ---------------- queueing ----------------

    def test_a_second_message_joins_the_queue_instead_of_asking_again(self):
        # One unreachable peer fails every queued message, one callback each.
        # Without batching the first would start a request while the rest hit the
        # throttle and propagated immediately — and then the request would
        # succeed and re-send messages already on their way to a node.
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=True))
        resolved = []
        first = self.module.queue_failed_message_for_path_retry(
            PEER_HASH, lambda: resolved.append("a"), lambda: None,
        )
        second = self.module.queue_failed_message_for_path_retry(
            PEER_HASH, lambda: resolved.append("b"), lambda: None,
        )
        self.assertTrue(first)
        self.assertTrue(second)

        deadline = threading.Event()
        deadline.wait(2.0)
        self.assertEqual(["a", "b"], sorted(resolved), "both should resolve from one request")
        self.assertEqual(
            1,
            [name for name, _ in self.events].count("request_path"),
            "the network should be asked once, not once per message",
        )

    def test_a_throttled_destination_is_refused_rather_than_left_waiting(self):
        # Refusal must not mean silence: the caller has to act now.
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=True))
        self.module._path_retry_attempts[PEER_HASH] = {"count": 1, "last": 1e12}
        queued = self.module.queue_failed_message_for_path_retry(
            PEER_HASH, lambda: None, lambda: None,
        )
        self.assertFalse(queued)

    def test_a_full_queue_is_refused_rather_than_dropping_the_message(self):
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=True))
        self.module._path_retry_inflight.add(PEER_HASH)
        self.module._path_retry_pending[PEER_HASH] = [
            (lambda: None, lambda: None)
        ] * self.module.PATH_RETRY_MAX_PENDING
        queued = self.module.queue_failed_message_for_path_retry(
            PEER_HASH, lambda: None, lambda: None,
        )
        self.assertFalse(queued)

    def test_resolving_clears_the_queue_and_the_in_flight_marker(self):
        self.module._path_retry_inflight.add(PEER_HASH)
        self.module._path_retry_pending[PEER_HASH] = [(lambda: None, lambda: None)]
        self.module._resolve_path_retry(PEER_HASH, path_found=True)
        self.assertNotIn(PEER_HASH, self.module._path_retry_pending)
        self.assertNotIn(PEER_HASH, self.module._path_retry_inflight)

    # ---------------- the ladder ----------------

    def test_rediscovery_is_attempted_before_propagation(self):
        # The invisible regression: with these reversed the message still
        # arrives, but via a node that was never needed.
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=False))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=3), "should still reach propagation")

        names = [name for name, _ in self.events]
        self.assertIn("expire_path", names)
        self.assertIn("request_path", names)
        self.assertLess(
            names.index("request_path"),
            names.index("submit"),
            "the route must be asked for before the message is handed to a node",
        )

    def test_a_rediscovered_route_retries_direct_and_leaves_propagation_armed(self):
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=True))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=3))

        # Re-sent as the same object, still DIRECT — not handed to a node.
        self.assertEqual(("submit", 2), self.events[-1])
        self.assertEqual(2, message.desired_method, "desired_method must not change")
        self.assertEqual(0, message.delivery_attempts, "send state should be reset")
        self.assertIsNone(message.packed)
        self.assertTrue(
            message.try_propagation_on_fail,
            "propagation must stay armed in case the retry fails too",
        )
        self.assertEqual([], [n for n, _ in self.events if n == "failed"])

    def test_a_failing_direct_retry_then_falls_through_to_propagation(self):
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=True))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=3))
        self.assertEqual(1, router.submits)

        # The retry fails in turn.
        router.submitted.clear()
        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=3), "propagation should now run")
        self.assertEqual(3, message.desired_method, "second failure goes to the node")
        self.assertIn("retrying", [name for name, _ in self.events])

    def test_rediscovery_is_attempted_only_once_per_message(self):
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=True))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=3))
        router.submitted.clear()
        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=3))

        self.assertEqual(
            1,
            [name for name, _ in self.events].count("request_path"),
            "a message gets one rediscovery, then propagation",
        )

    def test_an_already_propagated_message_does_not_trigger_rediscovery(self):
        # It failed because the propagation node was unreachable, not the
        # recipient, and LXMF asks for and retries the node's own path itself.
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=True))
        message = Message(desired_method=3)
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)

        self.assertNotIn("request_path", [name for name, _ in self.events])
        self.assertEqual(["failed"], [n for n, _ in self.events if n in ("failed", "retrying")])

    def test_without_a_propagation_node_no_route_means_plain_failure(self):
        setattr(self.rns, "Transport", FakeTransport(self.events, path_appears=False))
        message = Message()
        router = Router(self.events)
        self._attach(message, router, try_propagation=False)

        message.failed_callback(message)

        # The rediscovery still runs; only the fallback is unavailable.
        for _ in range(40):
            if any(n == "failed" for n, _ in self.events):
                break
            threading.Event().wait(0.1)
        self.assertIn("request_path", [name for name, _ in self.events])
        self.assertIn("failed", [name for name, _ in self.events])
        self.assertEqual(0, router.submits)

    def test_a_delivery_clears_the_peer_budget(self):
        self.module._path_retry_attempts[PEER_HASH] = {"count": 3, "last": 1e12}
        self.module.clear_path_retry_budget(PEER_HASH)
        self.assertNotIn(PEER_HASH, self.module._path_retry_attempts)

    # ---------------- rung 3: the propagation node's own route ----------------

    def test_a_failed_propagated_message_asks_about_the_propagation_node(self):
        # The node was the one destination in the ladder with no recovery: a
        # failed propagated message simply stopped, and nothing asked where the
        # node had gone — while everything else falls back to it.
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        self._reach_propagated_failure(message, router)

        self._wait_until(
            lambda: self._asked_about(NODE_HASH),
            message="nothing asked about the propagation node",
        )
        self.assertEqual(["expire_path", "request_path"], self._asked_about(NODE_HASH))

    def test_a_recovered_node_route_resubmits_the_message(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events, reachable=[NODE_HASH]))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        self._reach_propagated_failure(message, router)

        self._wait_until(
            lambda: router.submits >= 2,
            message="the message was not re-sent after the node's route came back",
        )
        self.assertEqual(("submit", 3), self.events[-1], "re-sent via propagation")
        self.assertEqual(
            2,
            [name for name, _ in self.events].count("retrying"),
            "the UI should be told about the second propagation attempt too",
        )
        self.assertNotIn("failed", [name for name, _ in self.events])

    def test_a_node_route_that_never_comes_back_fails_the_message(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        self._reach_propagated_failure(message, router)

        self._wait_until(
            lambda: "failed" in [name for name, _ in self.events],
            message="the message never became terminally failed",
        )
        self.assertEqual(1, router.submits, "nothing should be re-sent blind")

    def test_the_node_is_only_asked_about_once(self):
        # The resubmit registers a terminal callback, so a second propagated
        # failure after the node's route was rebuilt is a real failure.
        setattr(self.rns, "Transport", SelectiveTransport(self.events, reachable=[NODE_HASH]))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        self._reach_propagated_failure(message, router)
        self._wait_until(lambda: router.submits >= 2, message="no resubmit")

        message.failed_callback(message)

        self._wait_until(
            lambda: "failed" in [name for name, _ in self.events],
            message="the second propagated failure should be terminal",
        )
        self.assertEqual(
            1,
            self._asked_about(NODE_HASH).count("request_path"),
            "the node should not be asked about a second time",
        )

    def test_the_node_and_the_peer_keep_separate_budgets(self):
        # Keyed on whichever hash is being asked about, so an unreachable peer
        # cannot spend the node's attempts or the other way round.
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        self._reach_propagated_failure(message, router)
        self._wait_until(
            lambda: NODE_HASH in self.module._path_retry_attempts,
            message="the node was never given a budget of its own",
        )

        attempts = self.module._path_retry_attempts
        self.assertEqual(1, attempts[PEER_HASH]["count"])
        self.assertEqual(1, attempts[NODE_HASH]["count"])

    def test_a_propagated_message_the_node_accepts_clears_the_nodes_budget(self):
        # SENT on a propagated message means the node accepted it, so the node
        # is what just proved reachable.
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        self.module._path_retry_attempts[NODE_HASH] = {
            "count": self.module.PATH_RETRY_MAX_ATTEMPTS, "last": time.time(),
        }
        message = Message(desired_method=3)
        router = Router(self.events)
        self._attach(message, router)

        message.state = 4  # SENT
        message.method = 3  # PROPAGATED
        message.delivery_callback(message)

        self.assertNotIn(
            NODE_HASH,
            self.module._path_retry_attempts,
            "the node's budget should be restored once it accepted a message",
        )

    def test_a_direct_delivery_does_not_clear_the_nodes_budget(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        self.module._path_retry_attempts[NODE_HASH] = {
            "count": self.module.PATH_RETRY_MAX_ATTEMPTS, "last": time.time(),
        }
        message = Message()
        router = Router(self.events)
        self._attach(message, router)

        message.state = 8  # DELIVERED
        message.delivery_callback(message)

        self.assertIn(
            NODE_HASH,
            self.module._path_retry_attempts,
            "only the hop that proved itself should be cleared",
        )
        self.assertNotIn(PEER_HASH, self.module._path_retry_attempts)

    def test_no_propagation_node_means_the_node_is_never_asked_about(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message()
        router = Router(self.events)
        router.outbound_propagation_node = None
        self._attach(message, router, try_propagation=False)

        message.failed_callback(message)

        self._wait_until(
            lambda: "failed" in [name for name, _ in self.events],
            message="should fail once rediscovery found nothing",
        )
        self.assertEqual([], self._asked_about(NODE_HASH))
        self.assertEqual(0, router.submits)

    # ---------------- rung 2: escalating an opportunistic send to a link ----------------

    def test_a_failed_opportunistic_send_escalates_to_a_link(self):
        # The rung that was missing. determineDeliveryMethod picks OPPORTUNISTIC
        # for any short message without attachments, which is most of them, and a
        # single packet builds no link — so no transport node along the way ever
        # gets a link-table entry, and a relayed LINKREQUEST whose proof never
        # arrives is a transport node's only self-healing trigger.
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message(desired_method=1)
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)

        self._wait_until(lambda: router.submits >= 1, message="nothing was re-sent")
        self.assertEqual(2, message.desired_method, "should be re-sent as DIRECT")
        self.assertEqual(("submit", 2), self.events[-1])

    def test_the_link_attempt_comes_before_any_path_request(self):
        # Ordering is the point: a path request cannot cross a hop, so asking
        # first wastes the airtime that the link attempt needs.
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message(desired_method=1)
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)
        self._wait_until(lambda: router.submits >= 1, message="no escalation")

        names = [name for name, _ in self.events]
        self.assertIn("submit", names)
        self.assertNotIn(
            "request_path",
            names[: names.index("submit")],
            "no path request should precede the link attempt",
        )

    def test_a_failed_link_then_falls_through_to_the_path_rung(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message(desired_method=1)
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)
        self._wait_until(lambda: router.submits >= 1, message="no escalation")
        # The escalated link fails in turn; LXMF would call this back.
        message.failed_callback(message)

        self._wait_until(
            lambda: self._asked_about(PEER_HASH),
            message="the path rung never ran after the link failed",
        )
        self.assertEqual(["expire_path", "request_path"], self._asked_about(PEER_HASH))

    def test_the_escalation_is_only_attempted_once(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message(desired_method=1)
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)
        self._wait_until(lambda: router.submits >= 1, message="no escalation")
        message.failed_callback(message)
        self._wait_until(
            lambda: self._asked_about(PEER_HASH), message="no path rung",
        )
        message.failed_callback(message)

        self._wait_until(
            lambda: router.submits >= 2 or "failed" in [n for n, _ in self.events],
            message="the ladder stalled",
        )
        self.assertTrue(
            message._columba_link_escalation_attempted,
            "the marker must stop a second escalation",
        )

    def test_a_direct_send_is_not_escalated_and_goes_straight_to_the_path_rung(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message()  # DIRECT
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)

        self._wait_until(
            lambda: self._asked_about(PEER_HASH),
            message="a direct failure should ask about the route immediately",
        )
        self.assertFalse(getattr(message, "_columba_link_escalation_attempted", False))

    def test_an_already_propagated_message_is_not_escalated(self):
        setattr(self.rns, "Transport", SelectiveTransport(self.events))
        message = Message(desired_method=3)  # PROPAGATED
        router = Router(self.events)
        self._attach(message, router)

        message.failed_callback(message)

        self._wait_until(
            lambda: self._asked_about(NODE_HASH) or "failed" in [n for n, _ in self.events],
            message="the propagated branch stalled",
        )
        self.assertFalse(getattr(message, "_columba_link_escalation_attempted", False))
        self.assertEqual([], self._asked_about(PEER_HASH), "the peer is not the failed hop")


if __name__ == "__main__":
    unittest.main()
