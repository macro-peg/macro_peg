"""The finite controller must agree with string definitions, not the prototype."""
import itertools
import json
from pathlib import Path
import unittest

import fpp_finite


class FiniteFppTest(unittest.TestCase):
  def test_exhaustive_complete_border_chain(self):
    program = fpp_finite.build_program("ab#")
    program.validate()
    for n in range(12):
      for letters in itertools.product("ab", repeat=n):
        word = "".join(letters)
        expected = tuple(k for k in range(n - 1, 0, -1)
                         if word[:k] == word[-k:])
        result = program.run(word)
        self.assertEqual(result.borders, expected, word)
        self.assertLess(result.steps, 250 * (n + 1), word)

  def test_fpp(self):
    # Execute the checked-in finite table, not a Python algorithm callback.
    path = Path(__file__).parent / "generated" / "fpp-offline-controller.json"
    artifact = json.loads(path.read_text())
    program = fpp_finite.Program(artifact["alphabet"])
    program.code = artifact["code"]
    program.start = artifact["start"]
    program.validate()
    for n in range(10):
      for letters in itertools.product("ab", repeat=n):
        word = "".join(letters)
        result = program.run(word + "#" + word[::-1])
        expected = tuple(k for k in range(n, 0, -1)
                         if word[:k] == word[:k][::-1])
        self.assertEqual(result.borders, expected, word)

  def test_long_fallback_and_queue_refill(self):
    program = fpp_finite.build_program("ab#")
    for n in (32, 128, 512, 2048):
      for word in ("a" * n, "a" * n + "b", "ab" * n,
                   "b" * n + "a" + "b" * (n // 2) + "aa" + "b" * n):
        expected = tuple(k for k in range(len(word) - 1, 0, -1)
                         if word[:k] == word[-k:])
        result = program.run(word)
        self.assertEqual(result.borders, expected)
        self.assertLess(result.steps, 250 * (len(word) + 1))


if __name__ == "__main__":
  unittest.main()
