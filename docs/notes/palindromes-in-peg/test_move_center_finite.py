"""Local nonchain center selection must preserve text and avoid coordinates."""
from itertools import product
import unittest
import json
from pathlib import Path

from fpp_finite import BLANK, Program
from dp_search_finite import WINDOW
from move_center_finite import build_move_center_program, boundary, decode


class MoveCenterTest(unittest.TestCase):
  def check(self, p, word, base=5):
    tapes = [{} for _ in range(p.ntapes)]
    positions = [0] * p.ntapes
    left, right = base, base + len(word) - 1
    for i, c in enumerate(word, left):
      tapes[WINDOW][i] = ("C:" if i % 2 else "P:") + c
    tapes[WINDOW][left] = boundary(word[0], left=True, right=left == right)
    if left != right:
      tapes[WINDOW][right] = boundary(word[-1], right=True)
    tapes[WINDOW][left - 1], tapes[WINDOW][right + 1] = "outside-left", "outside-right"
    positions[WINDOW] = right
    result = p.execute(tapes, positions, 700 * (len(word) + 1))
    longest = max(k for k in range(1, len(word) + 1, 2)
                  if word[-k:] == word[-k:][::-1])
    center = right - (longest - 1) // 2
    self.assertEqual(result.positions[WINDOW], center, word)
    self.assertEqual([decode(result.tapes[WINDOW][i]) for i in range(left, right + 1)], list(word))
    for i in range(left, right + 1):
      expected = ("CRR:" if i == center == right else "C:" if i == center else
                  "RR:" if i == right else "") + word[i-left]
      self.assertEqual(result.tapes[WINDOW][i], expected, (word, i))
    self.assertEqual(result.tapes[WINDOW][left - 1], "outside-left")
    self.assertEqual(result.tapes[WINDOW][right + 1], "outside-right")
    for tape in range(p.ntapes):
      if tape != WINDOW:
        self.assertEqual(result.positions[tape], 0)
        self.assertTrue(all(s == BLANK for s in result.tapes[tape].values()), (word, tape))
    self.assertLess(result.steps, 500 * (len(word) + 1))
    return result.steps

  def test_all_short_binary_intervals(self):
    p = build_move_center_program("ab")
    for n in range(1, 10):
      for chars in product("ab", repeat=n):
        self.check(p, "".join(chars))

  def test_place_intervals_and_translation_do_not_change_local_work(self):
    p = build_move_center_program("abs")
    for word in ("a", "s", "asbsa", "asbsasbsa", "ssabss", "ab" * 64, "a" * 513):
      self.assertEqual(self.check(p, word, 11), self.check(p, word, 100011))

  def test_colon_is_a_valid_source_symbol(self):
    p = build_move_center_program("a:")
    for word in (":", "a:", ":::a:", ":a:"):
      self.check(p, word)

  def test_saved_table_runs_without_the_builder(self):
    artifact = json.loads((Path(__file__).parent / "generated/move-center-controller.json").read_text())
    p = Program(artifact["alphabet"], artifact["ntapes"])
    p.code, p.start = artifact["code"], artifact["start"]
    p.validate()
    for word in ("a", "asbsa", "ab" * 17, "a" * 32 + "b" + "a" * 31):
      self.check(p, word, 0)

  def test_reuses_the_same_scratch_tapes(self):
    p = build_move_center_program("abs")
    tapes = [{} for _ in range(p.ntapes)]
    positions = [0] * p.ntapes
    for word in ("asbsa" * 30, "b", "abs", "a" * 64, "sas"):
      tapes[WINDOW] = dict(enumerate(word))
      tapes[WINDOW][0] = boundary(word[0], left=True, right=len(word) == 1)
      if len(word) > 1:
        tapes[WINDOW][len(word) - 1] = boundary(word[-1], right=True)
      positions[WINDOW] = len(word) - 1
      result = p.execute(tapes, positions, 700 * (len(word) + 1))
      longest = max(k for k in range(1, len(word) + 1, 2) if word[-k:] == word[-k:][::-1])
      self.assertEqual(result.positions[WINDOW], len(word)-1-(longest-1)//2)
      for t in range(p.ntapes):
        if t != WINDOW:
          self.assertEqual(positions[t], 0)
          self.assertTrue(all(s == BLANK for s in tapes[t].values()))


if __name__ == "__main__":
  unittest.main()
