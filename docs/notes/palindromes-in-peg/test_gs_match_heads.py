import itertools
import random
import unittest

from gs_match_heads import compile_matcher, StreamingMatcher


class GSMatchHeadsTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    cls.program = compile_matcher()

  def test_incremental_matches_and_overlap_restart(self):
    rng = random.Random(910)
    patterns = ["".join(w) for n in range(1, 7) for w in itertools.product("ab", repeat=n)]
    for pattern in patterns:
      text = "".join(rng.choice("ab") for _ in range(60))
      self.compare(pattern, text)

  def test_nonempty_short_prefix_and_periodic_reuse(self):
    patterns = ["a" * 8 + "b", "a" * 8 + "b" + "a" * 8 + "c",
                ("a" * 8 + "b") * 8 + "c"]
    for pattern in patterns:
      self.compare(pattern, pattern * 20)
      self.compare(pattern, "b" * 200 + pattern * 7)

  def test_initial_short_prefix_offset_waits_for_real_input(self):
    pattern = ("a" * 8 + "b") * 8 + "b"
    vm = StreamingMatcher(pattern, self.program)
    vm.drain()
    self.assertGreater(vm.positions["Cut"], 0)
    self.assertTrue(vm.waiting)
    self.assertEqual(vm.positions["B"], len(pattern))
    self.compare(pattern, pattern * 2)

  def compare(self, pattern, text):
    vm = StreamingMatcher(pattern, self.program)
    vm.drain()
    for end, char in enumerate(text, 1):
      vm.append(char)
      before = len(vm.outputs)
      vm.drain()
      expected = end >= len(pattern) and text[end - len(pattern):end] == pattern
      self.assertEqual(vm.outputs[before:], [end] if expected else [],
                       (pattern, end))


if __name__ == "__main__":
  unittest.main()
