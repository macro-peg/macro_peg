"""Whole-prefix palindrome correctness using finite FPP, without KMP addresses."""
from itertools import product
import unittest

from scaffold_pal import run


class ScaffoldPalTest(unittest.TestCase):
  def test_all_binary_prefixes_through_length_six(self):
    for n in range(7):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        outputs, _ = run(word)
        self.assertEqual(outputs, [int(word[:i] == word[:i][::-1]) for i in range(1, n+1)], word)

  def test_online_control_handles_both_parities_and_multiple_fpp_restarts(self):
    for word in ("abbaabba", "ababaabababa", "aaaaaaa", "abbbbbbbba", "aababbabbaa"):
      outputs, stats = run(word)
      self.assertEqual(outputs, [int(word[:i] == word[:i][::-1]) for i in range(1, len(word)+1)], word)
      self.assertGreater(stats["fpp_calls"], 0)
      self.assertLessEqual(stats["radius"], 391)
      self.assertLessEqual(stats["fields"], 396)

  def test_fixed_work_does_not_drain_an_unbounded_backlog(self):
    outputs, stats = run("ab" * 10, budget=3)
    self.assertEqual(len(outputs), 20)
    self.assertEqual(stats["steps"], 60)
    self.assertEqual(stats["max_microsteps"], 3)
    # A bounded node count alone is not real-time recognition: this concrete
    # positive is missed while the real finite fallback is still executing.
    self.assertEqual(run("aa", budget=3)[0], [1, 0])


if __name__ == "__main__":
  unittest.main()
