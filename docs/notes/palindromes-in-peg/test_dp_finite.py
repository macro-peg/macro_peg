"""Oracle: two marked prefix lengths, with a strict unary lower bound."""
import itertools
import json
from pathlib import Path
import unittest

import dp_finite


def expected(word, lower):
  return next((h for h in range(lower + 1, (len(word) - 1) // 4 + 1)
               if word[:2*h+1] == word[:2*h+1][::-1]
               and word[:4*h+1] == word[:4*h+1][::-1]), None)


class DoublePalindromeTest(unittest.TestCase):
  def test_all_short_words_and_lower_bounds(self):
    machine = dp_finite.build_dp_program()
    for n in range(11):
      for symbols in itertools.product("ab", repeat=n):
        word = "".join(symbols)
        for lower in range(n // 4 + 2):
          result = machine.run(word, lower)
          self.assertEqual(result.h, expected(word, lower), (word, lower))
          self.assertLess(result.steps, 400 * (n + 1), (word, lower))

  def test_place_encoded_windows(self):
    artifact = json.loads((Path(__file__).parent / "generated" /
                           "dp-place-controller.json").read_text())
    machine = dp_finite.DpProgram(artifact["alphabet"], artifact["ntapes"])
    machine.source_alphabet = artifact["source_alphabet"]
    machine.code, machine.start = artifact["code"], artifact["start"]
    machine.found = artifact["found"]
    machine.validate()
    for n in range(1, 9):
      for symbols in itertools.product("ab", repeat=n):
        # s is Galil's special inter-symbol place, renamed from reserved _.
        word = "s".join(symbols)
        for lower in (0, 1, 2, n):
          self.assertEqual(machine.run(word, lower).h, expected(word, lower))

  def test_long_chain_skips_strict_lower_bound(self):
    machine = dp_finite.build_dp_program()
    for n in (32, 128, 512, 2048):
      word = "a" * n
      for lower in (0, 1, n // 8, n // 4):
        result = machine.run(word, lower)
        self.assertEqual(result.h, expected(word, lower))
        self.assertLess(result.steps, 400 * (n + 1))


if __name__ == "__main__":
  unittest.main()
