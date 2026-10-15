import json
import tempfile
import unittest
from pathlib import Path

import aggregate_latency


class AggregateLatencyTest(unittest.TestCase):

    def sender(self, sample="sample-1", tx="aa"):
        return {
            "experimentId": "exp",
            "scheme": "Xun",
            "route": "ETH-MYC",
            "round": 1,
            "sampleId": sample,
            "t0EpochMs": 1000,
            "scheduleLatenessMs": 0,
            "sourceProduct": "ethereum3",
            "sourceBlockchainId": "eth.id",
            "sourceTxHashKey": tx,
            "sendStatus": "SENT",
        }

    def events(self):
        common = {"ucpId": "ucp", "sourceProduct": "ethereum3", "sourceBlockchainId": "eth.id"}
        return [
            {**common, "event": "TARGET_CONFIRMED", "eventTimeEpochMs": 1600, "targetTxHash": "bb"},
            {**common, "event": "SOURCE_ACCEPTED", "eventTimeEpochMs": 1200, "sourceTxHashKey": "aa"},
            {**common, "event": "TARGET_SUBMITTING", "eventTimeEpochMs": 1400},
            {**common, "event": "TARGET_CONFIRMED", "eventTimeEpochMs": 1700, "targetTxHash": "duplicate"},
        ]

    def test_normal_out_of_order_and_duplicate_events(self):
        rows = aggregate_latency.aggregate([self.sender()], self.events())
        self.assertEqual("OK", rows[0]["status"])
        self.assertEqual(200, rows[0]["Ts"])
        self.assertEqual(200, rows[0]["To"])
        self.assertEqual(200, rows[0]["Tt"])
        self.assertEqual(600, rows[0]["Ttotal"])
        self.assertEqual(600, rows[0]["Ts"] + rows[0]["To"] + rows[0]["Tt"])
        self.assertEqual("bb", rows[0]["targetTxHash"])

    def test_missing_and_negative_are_not_silently_dropped(self):
        missing = aggregate_latency.aggregate([self.sender()], [])
        self.assertEqual("MISSING_T1", missing[0]["status"])

        negative_events = self.events()
        negative_events[1]["eventTimeEpochMs"] = 900
        negative = aggregate_latency.aggregate([self.sender()], negative_events)
        self.assertEqual("NEGATIVE_OR_INCONSISTENT_LATENCY", negative[0]["status"])

    def test_log_reader_filters_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "relayer.log"
            event = self.events()[0]
            path.write_text("ordinary log\n2026 INFO EXPERIMENT_METRIC " + json.dumps(event) + "\n", encoding="utf-8")
            self.assertEqual([event], aggregate_latency.read_jsonl(path, relayer=True))

    def test_zero_wait_preserves_missing_terminal_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sender_path = root / "sender.jsonl"
            relayer_path = root / "relayer.log"
            sender_path.write_text(json.dumps(self.sender()) + "\n", encoding="utf-8")
            relayer_path.write_text("", encoding="utf-8")
            _, _, rows = aggregate_latency.wait_for_terminal_records(
                [sender_path], [relayer_path], 0, 0.01
            )
            self.assertEqual("MISSING_T1", rows[0]["status"])

    def test_pooled_summary_keeps_round_mean_and_pooled_statistics(self):
        first = aggregate_latency.aggregate([self.sender("sample-1", "aa")], self.events())[0]
        second_sender = self.sender("sample-2", "cc")
        second_sender["round"] = 2
        second_events = [dict(event) for event in self.events()]
        for event in second_events:
            event["ucpId"] = "ucp-2"
            if event["event"] == "SOURCE_ACCEPTED":
                event["sourceTxHashKey"] = "cc"
            event["eventTimeEpochMs"] += 100
        second = aggregate_latency.aggregate([second_sender], second_events)[0]
        per_round = aggregate_latency.summaries([first, second], 1)
        pooled = aggregate_latency.pooled_summaries([first, second], per_round)
        self.assertEqual(2, pooled[0]["rounds"])
        self.assertEqual(2, pooled[0]["validRounds"])
        self.assertEqual(250, pooled[0]["TsMeanOfRoundMeansMs"])
        self.assertEqual(250, pooled[0]["TsPooledMeanMs"])


if __name__ == "__main__":
    unittest.main()
