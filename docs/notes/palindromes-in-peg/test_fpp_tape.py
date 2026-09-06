"""Independent oracles for the Fischer–Paterson local-head experiment."""
import itertools
import unittest

import fpp_tape
from fpp_tape import border_machine


class FischerPatersonTest(unittest.TestCase):
  def test_single_head_lowering(self):
    self.assertTrue(hasattr(fpp_tape, "single_head_border_machine"),
                    "append-only shared tape must lower to independent tapes")
    for n in range(12):
      for letters in itertools.product("ab", repeat=n):
        word = "".join(letters)
        expected = tuple(b for b in range(n - 1, 0, -1)
                         if word[:b] == word[-b:])
        result = fpp_tape.single_head_border_machine(word, True)
        self.assertEqual(result.borders, expected, word)
        self.assertLessEqual(result.operations, 200 * (n + 1), word)

  def test_all_initial_palindromes(self):
    self.assertTrue(hasattr(fpp_tape, "initial_palindromes"),
                    "local-head FPP must extract the complete border chain")
    for n in range(12):
      for letters in itertools.product("ab", repeat=n):
        word = "".join(letters)
        expected = tuple(k for k in range(n, 0, -1)
                         if word[:k] == word[:k][::-1])
        for single_head in (False, True):
          result = fpp_tape.initial_palindromes(word, single_head=single_head)
          self.assertEqual(result.borders, expected, word)
          self.assertLessEqual(result.operations, 300 * (n + 1), word)

  def test_every_binary_word_through_length_twelve(self):
    for n in range(13):
      for letters in itertools.product("ab", repeat=n):
        word = "".join(letters)
        expected = max((b for b in range(1, n)
                        if word[:b] == word[-b:]), default=0)
        result = border_machine(word)
        self.assertEqual(result.border, expected, word)
        self.assertLessEqual(result.operations, 100 * (n + 1), word)

  def test_nonperiodic_palindromes(self):
    for n in range(1, 22):
      for letters in itertools.product("ab", repeat=(n + 1) // 2):
        half = "".join(letters)
        word = half + (half[:-1] if n % 2 else half)[::-1]
        expected = max((b for b in range(1, n)
                        if word[:b] == word[-b:]), default=0)
        if n - expected > n / 2:
          self.assertEqual(border_machine(word).border, expected, word)

  def test_long_runs_and_repeated_fallback(self):
    for n in (16, 64, 256, 1024, 4096):
      words = ["a" * n, "a" * (n - 1) + "b", "ab" * n,
               "b" * n + "a" + "b" * (n // 2) + "aa" + "b" * n]
      for word in words:
        expected = max((b for b in range(1, len(word))
                        if word[:b] == word[-b:]), default=0)
        result = border_machine(word)
        self.assertEqual(result.border, expected, word[:60])
        self.assertLessEqual(result.operations, 100 * (len(word) + 1))
        single = fpp_tape.single_head_border_machine(word, True)
        self.assertEqual(single.border, expected, word[:60])
        self.assertLessEqual(single.operations, 200 * (len(word) + 1))


if __name__ == "__main__":
  unittest.main()
