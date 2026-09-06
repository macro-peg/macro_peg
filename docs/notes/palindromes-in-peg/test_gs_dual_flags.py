import itertools
import random
import unittest

from gs_dual_flags import DualFlagVM, compile_dual_flags
from gs_local_clock import DEFAULT_DUAL


class DualFlagsTest(unittest.TestCase):
  def test_palindrome_prefix_intervals_and_single_length_clock(self):
    program = compile_dual_flags()
    rng = random.Random(921)
    words = ["".join(w) for n in range(10) for w in itertools.product("ab", repeat=n)]
    words += ["".join(rng.choice("ab") for _ in range(512)) for _ in range(30)]
    words += ["a" * 2048, "ab" * 2048, ("a" * 8 + "b") * 8 + "b"]
    for word in words:
      for lower in (0, len(word) // 2, 3 * len(word) // 4):
        observer = DualFlagVM(word, lower, len(word), program)
        expected = tuple(word[:n] == word[:n][::-1]
                         for n in range(len(word) - 1, lower - 1, -1))
        self.assertEqual(observer.run(), expected, (word[:30], len(word), lower))
        self.assertLessEqual(observer.steps, DEFAULT_DUAL.flag_view * max(1, len(word)) + 1)


if __name__ == "__main__":
  unittest.main()
