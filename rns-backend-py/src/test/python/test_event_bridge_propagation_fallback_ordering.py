import importlib.util
import sys
import threading
import time
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class Callback:
    def __init__(self, events, name):
        self.events = events
        self.name = name

    def onEvent(self, payload):
        self.events.append((self.name, payload["hash"]))


class PayloadCallback:
    def __init__(self, events, name):
        self.events = events
        self.name = name

    def onEvent(self, payload):
        self.events.append((self.name, payload.copy()))


class Message:
    def __init__(self):
        self.hash = b"message-hash"
        self.method = 2
        self.desired_method = 2
        self.state = 1
        self.try_propagation_on_fail = False
        self.delivery_attempts = 4
        self.packed = b"packed"
        self.propagation_packed = b"propagated"
        self.propagation_stamp = b"stamp"
        self.defer_propagation_stamp = False
        self.delivery_callback = None
        self.failed_callback = None

    def register_delivery_callback(self, callback):
        self.delivery_callback = callback

    def register_failed_callback(self, callback):
        self.failed_callback = callback


class PinnedLockRouter:
    outbound_propagation_node = b"relay"

    def __init__(self, events):
        self.events = events
        self.outbound_processing_lock = threading.Lock()
        self.submitted = threading.Event()

    def process_failure(self, message):
        with self.outbound_processing_lock:
            message.failed_callback(message)
            self.events.append(("callback_returned", message.desired_method))

    def handle_outbound(self, message):
        with self.outbound_processing_lock:
            # Pinned LXMF repacks the same shared object for the fallback and
            # makes the effective method PROPAGATED before relay acceptance.
            message.method = message.desired_method
            message.state = 4
            self.events.append(("submit", message.desired_method))
            self.submitted.set()


class PropagationFallbackOrderingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_DEBUG", 7)
        setattr(rns, "log", lambda *args, **kwargs: None)
        # Rung 3 asks about the propagation node when a propagated send fails,
        # so the worker needs a Transport. Nothing ever answers here: these
        # tests are about the ladder's ordering, not about route recovery.
        setattr(rns, "Transport", types.SimpleNamespace(
            expire_path=lambda destination_hash: None,
            request_path=lambda destination_hash: None,
            has_path=lambda destination_hash: False,
        ))
        sys.modules["RNS"] = rns

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

        spec = importlib.util.spec_from_file_location("event_bridge_fallback_test", EVENT_BRIDGE_PATH)
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        # Per-destination state is module level and the throttle is one request
        # per minute, so without this the second test to ask about b"relay"
        # would be refused and fail synchronously — the ordering under test
        # would depend on which test ran first.
        setattr(self.module, "_path_retry_pending", {})
        setattr(self.module, "_path_retry_inflight", set())
        setattr(self.module, "_path_retry_attempts", {})
        setattr(self.module, "PATH_RETRY_TIMEOUT_SECONDS", 0.3)

    def _wait_for(self, events, name, timeout=3.0):
        """Wait for an asynchronously-emitted event, rather than assuming it is
        already there. Rung 3 resolves on its own worker thread."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(event[0] == name for event in events):
                return True
            time.sleep(0.01)
        return False

    def test_pinned_non_reentrant_router_lock_does_not_deadlock_or_reenter(self):
        events = []
        message = Message()
        router = PinnedLockRouter(events)
        setattr(self.module, "_lxmf_router", router)
        self.module.attach_lxmessage_callbacks(
            message,
            Callback(events, "delivered"),
            Callback(events, "failed"),
            Callback(events, "retrying"),
            try_propagation_on_fail=True,
        )

        callback_thread = threading.Thread(target=router.process_failure, args=(message,))
        callback_thread.start()
        callback_thread.join(timeout=1)
        self.assertFalse(callback_thread.is_alive(), "failure callback deadlocked under pinned router lock")
        self.assertTrue(router.submitted.wait(timeout=1), "deferred fallback was not submitted")

        names = [event[0] for event in events]
        self.assertLess(names.index("callback_returned"), names.index("submit"))
        self.assertLess(names.index("retrying"), names.index("submit"))
        self.assertEqual(3, message.desired_method)
        self.assertEqual(0, message.delivery_attempts)
        self.assertIsNone(message.packed)
        self.assertIsNone(message.propagation_packed)

    def test_duplicate_primary_failure_owns_one_enqueue(self):
        events = []
        message = Message()
        router = PinnedLockRouter(events)
        setattr(self.module, "_lxmf_router", router)
        self.module.attach_lxmessage_callbacks(
            message,
            Callback(events, "delivered"),
            Callback(events, "failed"),
            Callback(events, "retrying"),
            try_propagation_on_fail=True,
        )

        message.failed_callback(message)
        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=1))
        self.assertEqual(1, [event[0] for event in events].count("submit"))

    def test_delayed_primary_proof_overrides_propagated_repack_and_acceptance(self):
        events = []
        message = Message()
        router = PinnedLockRouter(events)
        setattr(self.module, "_lxmf_router", router)
        self.module.attach_lxmessage_callbacks(
            message,
            PayloadCallback(events, "delivered"),
            PayloadCallback(events, "failed"),
            PayloadCallback(events, "retrying"),
            try_propagation_on_fail=True,
            originating_identity_hash="identity-a",
        )

        self.assertTrue(callable(message.failed_callback))
        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=1))
        self.assertTrue(callable(message.delivery_callback))
        message.delivery_callback(message)
        message.state = 8
        message.delivery_callback(message)

        deliveries = [payload for name, payload in events if name == "delivered"]
        self.assertEqual([4, 8], [payload["state"] for payload in deliveries])
        self.assertEqual([3, 3], [payload["method"] for payload in deliveries])
        self.assertEqual([3, 3], [payload["desired_method"] for payload in deliveries])
        self.assertEqual(
            ["identity-a", "identity-a"],
            [payload["originating_identity_hash"] for payload in deliveries],
        )

    def test_failed_fallback_can_be_promoted_by_delayed_primary_proof(self):
        events = []
        message = Message()
        router = PinnedLockRouter(events)
        setattr(self.module, "_lxmf_router", router)
        self.module.attach_lxmessage_callbacks(
            message,
            PayloadCallback(events, "delivered"),
            PayloadCallback(events, "failed"),
            PayloadCallback(events, "retrying"),
            try_propagation_on_fail=True,
        )

        self.assertTrue(callable(message.failed_callback))
        message.failed_callback(message)
        self.assertTrue(router.submitted.wait(timeout=1))
        self.assertTrue(callable(message.failed_callback))
        message.failed_callback(message)
        # Rung 3 now sits between the propagated failure and giving up: the node
        # is asked about on a worker thread, and nothing answers in this stub, so
        # the terminal failure arrives shortly rather than inline.
        self.assertTrue(
            self._wait_for(events, "failed"),
            "the propagated failure never became terminal after node rediscovery",
        )
        message.state = 8
        self.assertTrue(callable(message.delivery_callback))
        message.delivery_callback(message)

        self.assertEqual(
            # Only one "retrying": rung 3 emits a second one when the node's
            # route comes back, and nothing answers here.
            ["retrying", "failed", "delivered"],
            [name for name, _ in events if name in ("retrying", "failed", "delivered")],
        )
        delivered = next(payload for name, payload in events if name == "delivered")
        self.assertEqual(8, delivered["state"])
        self.assertEqual(3, delivered["method"])
        self.assertEqual(3, delivered["desired_method"])


if __name__ == "__main__":
    unittest.main()
