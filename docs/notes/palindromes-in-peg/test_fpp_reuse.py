"""Cleanup must work at real instruction boundaries, including half-writes."""
import copy
import json
from pathlib import Path
import unittest

from dp_finite import build_dp_program, LOWER, OUTPUT
from fpp_finite import Program, LEFT, END, BLANK
from fpp_subroutine import SOURCE, MARKS, build_marked_program
import fpp_reuse


def initial(p, word, lower):
  tapes = [{} for _ in range(p.ntapes)]
  tapes[SOURCE] = dict(enumerate(LEFT + word + END))
  tapes[LOWER] = dict(enumerate(LEFT + "1" * lower + END))
  return tapes, [0] * p.ntapes


class ReuseTest(unittest.TestCase):
  def test_reuses_nine_tape_marked_kernel(self):
    p = fpp_reuse.make_reusable(build_marked_program())
    tapes, positions = [{} for _ in range(p.ntapes)], [0] * p.ntapes
    for word in ["abababa", "aaba", "", "a"]:
      tapes[SOURCE] = dict(enumerate(LEFT + word + END))
      before = dict(tapes[SOURCE])
      p.execute(tapes, positions, 1000 * (len(word) + 1))
      self.assertEqual([tapes[MARKS][i] for i in range(1, len(word) + 1)],
                       ["1" if word[:i] == word[:i][::-1] else "0"
                        for i in range(1, len(word) + 1)])
      p.execute(tapes, positions, 200 * (len(word) + 1), start=p.cleanup)
      self.assertEqual(positions, [0] * p.ntapes)
      self.assertEqual(tapes[SOURCE], before)
      for tape in p.scratch:
        self.assertTrue(all(v == BLANK for v in tapes[tape].values()))

  def test_wraps_saved_controller_table(self):
    path = Path(__file__).parent / "generated" / "dp-place-controller.json"
    data = json.loads(path.read_text())
    kernel = Program(data["alphabet"], data["ntapes"])
    kernel.code, kernel.start = data["code"], data["start"]
    kernel.source_alphabet, kernel.found = data["source_alphabet"], data["found"]
    p = fpp_reuse.make_reusable(kernel)
    tapes, positions = initial(p, "asasasasa", 0)
    result = p.execute(tapes, positions, 10000)
    self.assertEqual(result.state, p.found)

  def test_reuses_same_tapes_after_success_and_failure(self):
    p = fpp_reuse.make_reusable(build_dp_program())
    tapes, positions = initial(p, "a" * 65, 0)
    for word, lower in [("a" * 65, 0), ("ab", 0), ("a" * 9, 1), ("", 0)]:
      # Only the input driver changes input, never a scratch tape.
      tapes[SOURCE] = dict(enumerate(LEFT + word + END))
      tapes[LOWER] = dict(enumerate(LEFT + "1" * lower + END))
      result = p.execute(tapes, positions, 1000 * (len(word) + 1))
      want = next((h for h in range(lower + 1, (len(word) - 1) // 4 + 1)
                   if word[:2*h+1] == word[:2*h+1][::-1]
                   and word[:4*h+1] == word[:4*h+1][::-1]), None)
      self.assertEqual(result.state == p.found, want is not None)
      if want is not None:
        self.assertEqual(sum(v == "1" for v in tapes[OUTPUT].values()), want)
      clean = p.execute(tapes, positions, 200 * (len(word) + 1), start=p.cleanup)
      self.assertEqual(clean.positions, (0,) * p.ntapes)
      for tape in p.scratch:
        self.assertTrue(all(v == BLANK for v in tapes[tape].values()))

  def test_cancellation_at_every_instruction_boundary(self):
    p = fpp_reuse.make_reusable(build_dp_program())
    for word, lower in [("", 0), ("ababa", 0), ("aaaaab", 1)]:
      tapes, positions = initial(p, word, lower)
      e = p.execution(tapes, positions)
      while not e.done:
        saved = copy.deepcopy(e.tapes)
        before_source, before_lower = dict(saved[SOURCE]), dict(saved[LOWER])
        clean = p.execute(saved, list(e.positions), 200 * (len(word) + 1),
                          start=p.cancel_entries[e.state])
        self.assertEqual(clean.positions, (0,) * p.ntapes,
                         (word, e.state, e.steps))
        self.assertEqual(saved[SOURCE], before_source)
        self.assertEqual(saved[LOWER], before_lower)
        for tape in p.scratch:
          self.assertTrue(all(v == BLANK for v in saved[tape].values()),
                          (word, e.state, tape, saved[tape]))
        e.step()


if __name__ == "__main__":
  unittest.main()
