import itertools
import random
import unittest

from gs_flag_heads import compile_flags, FlagVM


class GSFlagHeadsTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    cls.program = compile_flags()

  def test_all_prefix_flags_and_intervals(self):
    rng = random.Random(911)
    words = ["".join(w) for n in range(9) for w in itertools.product("ab", repeat=n)]
    words += ["".join(rng.choice("ab") for _ in range(120)) for _ in range(30)]
    words += ["a" * 257, "ab" * 257, ("a" * 8 + "b") * 8 + "a" * 8]
    for word in words:
      for lower, upper in ((0, len(word) + 1), (0, len(word)),
                            (len(word) // 2, len(word))):
        vm = FlagVM(word, lower, upper, self.program)
        expected = tuple(word[:n] == word[:n][::-1] for n in range(upper - 1, lower - 1, -1))
        self.assertEqual(vm.run(), expected, (word[:30], len(word), lower, upper))


if __name__ == "__main__":
  unittest.main()
