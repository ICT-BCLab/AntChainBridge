import concurrent.futures
import os
import subprocess
import unittest
import uuid

import pymysql
from dioxide_tx_coordinator import Coordinator


def connection():
    return pymysql.connect(host="127.0.0.1", port=18236, user="root", password="",
                           database="isn_test", autocommit=True)


def coordinator(network):
    return Coordinator({"networkId": network, "checkpointHash": "fixture"}, connection)


class Transport:
    def checkpoint(self):
        return "fixture"

    def current_isn(self, account):
        return 181

    def compose_and_sign(self, isn):
        return isn.to_bytes(8, "big")

    def broadcast(self, signed):
        return "tx-" + str(int.from_bytes(signed, "big"))


def python_process(network, prefix):
    c = coordinator(network)
    return [c.submit(f"{prefix}-{i}", "account:ed25519", bytes([1, 2, 3]), Transport()) for i in range(32)]


@unittest.skipUnless(os.environ.get("ISN_TEST_MYSQL") == "1", "requires disposable MySQL")
class CoordinatorTest(unittest.TestCase):
    def setUp(self):
        self.network = "python-" + str(uuid.uuid4())

    def test_independent_intents_and_response_loss(self):
        class Lost(Transport):
            def broadcast(self, signed):
                raise TimeoutError()
        c = coordinator(self.network)
        with self.assertRaises(TimeoutError):
            c.submit("same", "account", b"payload", Lost())
        self.assertEqual("tx-181", coordinator(self.network).submit("same", "account", b"payload", Transport()))
        self.assertEqual("tx-182", c.submit("other", "account", b"payload", Transport()))
        with self.assertRaisesRegex(RuntimeError, "identity"):
            c.submit("same", "account", b"changed", Transport())

    def test_two_python_processes(self):
        with concurrent.futures.ProcessPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(python_process, self.network, prefix) for prefix in ["a", "b"]]
            values = [h for f in futures for h in f.result(timeout=60)]
        self.assertEqual(64, len(set(values)))

    @unittest.skipUnless(os.environ.get("JAVA_PROBE_CLASSPATH"), "requires compiled Java fixture")
    def test_two_java_and_two_python_processes_and_crash_recovery(self):
        args = [os.environ["JAVA_PROBE_JAVA"], "-cp", os.environ["JAVA_PROBE_CLASSPATH"],
                "com.alipay.antchain.bridge.plugins.lib.transactions.CoordinatorProcessProbe",
                "jdbc:mysql://127.0.0.1:18236/isn_test?allowPublicKeyRetrieval=true&useSSL=false",
                self.network]
        processes = [subprocess.Popen(args + [prefix, "32"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                     for prefix in ["j1", "j2"]]
        with concurrent.futures.ProcessPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(python_process, self.network, prefix) for prefix in ["p1", "p2"]]
            hashes = [h for f in futures for h in f.result(timeout=60)]
        for process in processes:
            stdout, stderr = process.communicate(timeout=60)
            self.assertEqual(0, process.returncode, stderr)
            hashes.extend(stdout.splitlines())
        self.assertEqual(128, len(set(hashes)))
        crashed = subprocess.run(args + ["crash", "1", "crash"], capture_output=True, timeout=30)
        self.assertEqual(17, crashed.returncode)
        # The Python implementation resumes Java's committed signed bytes, not a new ISN.
        recovered = coordinator(self.network).submit("crash-0", "account", bytes([1, 2, 3]), Transport())
        self.assertEqual("tx-309", recovered)
        with connection() as c, c.cursor() as cursor:
            cursor.execute("SELECT COUNT(*),COUNT(DISTINCT isn) FROM bridge_tx_submission WHERE network_id=%s", (self.network,))
            self.assertEqual((129, 129), cursor.fetchone())


if __name__ == "__main__":
    unittest.main()
