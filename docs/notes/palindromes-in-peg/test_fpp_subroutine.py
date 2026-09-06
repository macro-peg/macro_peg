"""FPP with actual input preparation and marked output on independent tapes."""
import itertools
import json
from pathlib import Path
import unittest

import fpp_subroutine


class FppSubroutineTest(unittest.TestCase):
  def test_marks_all_prefix_palindromes_from_plain_input(self):
    artifact = json.loads((Path(__file__).parent / "generated" /
                           "fpp-marked-controller.json").read_text())
    routine = fpp_subroutine.MarkedProgram(artifact["alphabet"], artifact["ntapes"])
    routine.source_alphabet = artifact["source_alphabet"]
    routine.code, routine.start = artifact["code"], artifact["start"]
    routine.validate()
    for n in range(11):
      for letters in itertools.product("ab", repeat=n):
        word = "".join(letters)
        result = routine.run(word)
        expected = tuple(int(word[:i] == word[:i][::-1])
                         for i in range(1, n + 1))
        self.assertEqual(result.marks, expected, word)
        self.assertEqual(result.prepared, word + "#" + word[::-1], word)
        self.assertEqual(result.positions[fpp_subroutine.MARKS], 0)
        self.assertLess(result.steps, 300 * (n + 1), word)

  def test_no_emit_or_host_reverse_in_transition_table(self):
    routine = fpp_subroutine.build_marked_program()
    self.assertTrue(all(row[0] in ("read", "write", "move", "halt")
                        for row in routine.code))
    routine.validate()

  def test_long_runs_and_periodic_inputs(self):
    routine = fpp_subroutine.build_marked_program()
    for n in (32, 128, 512, 2048):
      for word in ("a" * n, "ab" * n,
                   "b" * n + "a" + "b" * (n // 2) + "aa" + "b" * n):
        result = routine.run(word)
        expected = tuple(int(word[:i] == word[:i][::-1])
                         for i in range(1, len(word) + 1))
        self.assertEqual(result.marks, expected, word[:40])
        self.assertLess(result.steps, 300 * (len(word) + 1))


if __name__ == "__main__":
  unittest.main()
