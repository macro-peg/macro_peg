import itertools
import random
import unittest

from gs_dual_flags import compile_dual_flags, DualFlagVM
from gs_batch_clock import DEFAULT_BATCH


class BatchClockTest(unittest.TestCase):
  def test_all_four_job_intervals(self):
    p = compile_dual_flags(unit=False)
    rng = random.Random(1406)
    words = ["".join(w) for n in range(10) for w in itertools.product("ab", repeat=n)]
    words += ["".join(rng.choice("ab") for _ in range(n)) for n in (32, 64, 128, 256, 512) for _ in range(8)]
    words += ["a" * 2048, "ab" * 2048, ("a" * 8 + "b") * 80 + "b"]
    for word in words:
      for job in range(1, 5):
        half = max(1, len(word) // job)
        prefix = word[:job * half]
        if len(prefix) != job * half: continue
        lower = (job - 1) * half
        vm = DualFlagVM(prefix, lower, len(prefix), p)
        expected = tuple(prefix[:n] == prefix[:n][::-1] for n in range(len(prefix) - 1, lower - 1, -1))
        self.assertEqual(vm.run(), expected, (word[:30], len(word), job))
        self.assertLessEqual(vm.steps + 1, DEFAULT_BATCH.flags * half)


if __name__ == "__main__":
  unittest.main()
