import itertools
import random
import unittest

from gs_local_clock import DEFAULT
from gs_flag_heads import FlagVM, compile_flags
from gs_match_heads import StreamingMatcher, compile_matcher


class GSLocalClockTest(unittest.TestCase):
  def test_flag_jobs_fit_the_symbolic_head_bound(self):
    program = compile_flags()
    rng = random.Random(915)
    words = ["".join(w) for n in range(9) for w in itertools.product("ab", repeat=n)]
    words += ["".join(rng.choice("ab") for _ in range(512)) for _ in range(30)]
    word = "ab"
    for _ in range(4):
      word = (word + "a") * 8 + "b"
      words.append(word)
    for word in words:
      job = FlagVM(word, 0, len(word), program)
      job.run()
      self.assertLessEqual(job.steps, DEFAULT.flag_view * (2 * len(word) + 1) + 1,
                           (len(word), word[:40], job.steps))

  def test_matching_deadlines_include_preprocessing(self):
    program, budget = compile_matcher(), DEFAULT.matching // 4
    rng = random.Random(916)
    patterns = ["".join(w) for n in range(1, 7) for w in itertools.product("ab", repeat=n)]
    patterns += ["a" * 257, "ab" * 129, ("a" * 8 + "b") * 8 + "b"]
    patterns += ["".join(rng.choice("ab") for _ in range(257)) for _ in range(15)]
    for pattern in patterns:
      text = pattern * 3 + "".join(rng.choice("ab") for _ in range(50)) + pattern
      vm = StreamingMatcher(pattern, program)
      # Deliberately do not drain pattern preprocessing before the first
      # arrival. It must share the fixed per-arrival service budget.
      for end, char in enumerate(text, 1):
        before = len(vm.outputs)
        vm.append(char)
        for _ in range(budget):
          if vm.waiting:
            break
          vm.step()
        expected = end >= len(pattern) and text[end-len(pattern):end] == pattern
        self.assertEqual(vm.outputs[before:], [end] if expected else [],
                         (len(pattern), pattern[:30], end))


if __name__ == "__main__":
  unittest.main()
