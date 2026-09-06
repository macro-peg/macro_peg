"""Check source boundaries against independently decoded words/coordinates."""
import unittest

from galil_contracts import ContractAudit
from scaffold_galil import OnlineGalil


class GalilContractsTest(unittest.TestCase):
  def test_main_move_replay_and_chain_contracts(self):
    totals = {}
    for word in ("a" * 16, "ab" * 12, "abba" * 8,
                 "ab" + "a" * 20 + "ba", "a" * 12 + "b" + "a" * 12,
                 "ab" * 10 + "bbaa" + "ab" * 8,
                 "abba" * 6 + "bab" + "abba" * 5):
      source, audit = OnlineGalil(), ContractAudit()
      for i, char in enumerate(word, 1):
        result = source.read(char)
        while True:
          audit.observe(source, word[:i], result)
          if result.input_ready:
            break
          result = source.work()
      self.assertEqual(audit.counts["output"], len(word))
      self.assertEqual(audit.counts["move"], audit.counts["replay_return"])
      self.assertEqual(audit.counts["shift"], audit.counts["shift_return"])
      for row in audit.rows:
        if row["event"] == "output":
          for size in range(row["size"] + 1,
                            min(len(word), row["size"] + row["prediction"]) + 1):
            self.assertNotEqual(word[:size], word[:size][::-1], row)
      for name, count in audit.counts.items():
        totals[name] = totals.get(name, 0) + count
    for name in ("main", "dp_found", "move", "replay_start", "replay_return", "shift", "shift_return"):
      self.assertGreater(totals[name], 0, name)


if __name__ == "__main__":
  unittest.main()
