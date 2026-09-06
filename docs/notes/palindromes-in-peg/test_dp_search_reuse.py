"""Cancellation of the complete search, including internal cleanup/marking."""
import copy
import json
from pathlib import Path
import unittest

from fpp_finite import Program, LEFT, END, BLANK
from dp_finite import LOWER
from dp_search_finite import WINDOW, center_symbol, build_search_program
from test_dp_search_finite import expected
from dp_search_reuse import make_cancellable


class SearchReuseTest(unittest.TestCase):
  def test_saved_cancellable_table(self):
    path = Path(__file__).parent / "generated" / "dp-search-reusable-controller.json"
    data = json.loads(path.read_text())
    p = Program(data["alphabet"], data["ntapes"])
    for name in ("code", "start", "found", "missed", "cleanup", "scratch"):
      setattr(p, name, data[name])
    p.cancel_entries = {int(q): k for q, k in data["cancel_entries"].items()}
    p.validate()
    tapes = [{} for _ in range(p.ntapes)]
    tapes[WINDOW] = {0: LEFT, **{i: "a" for i in range(1, 13)},
                     13: center_symbol("a"), 14: END}
    tapes[LOWER] = {0: LEFT, 1: END}
    before = dict(tapes[WINDOW])
    heads = [0] * p.ntapes
    heads[WINDOW] = 13
    result = p.execute(tapes, heads, 10000)
    self.assertEqual(result.state, p.found)
    p.execute(tapes, heads, 10000, start=p.cancel_entries[result.state])
    self.assertEqual(tapes[WINDOW], before)
    for tape in p.scratch:
      self.assertTrue(all(v == BLANK for v in tapes[tape].values()))

  def test_cancel_every_instruction_including_cleanup_and_result_marks(self):
    p = make_cancellable(build_search_program("ab"))
    for prefix in ("a", "aabaa", "aabbbaaabbbaa"):
      tapes = [{} for _ in range(p.ntapes)]
      tokens = [LEFT, *prefix, "b", "a", END]
      tokens[len(prefix)] = center_symbol(prefix[-1])
      tapes[WINDOW] = dict(enumerate(tokens))
      tapes[LOWER] = {0: LEFT, 1: END}
      positions = [0] * p.ntapes
      positions[WINDOW] = len(prefix)
      machine = p.execution(tapes, positions)
      while True:
        saved = copy.deepcopy(tapes)
        heads = list(positions)
        p.execute(saved, heads, 2000 * (len(prefix) + 1),
                  start=p.cancel_entries[machine.state])
        self.assertEqual(saved[WINDOW], dict(enumerate(tokens)), machine.steps)
        self.assertEqual(saved[LOWER], {0: LEFT, 1: END})
        want_heads = [0] * p.ntapes
        want_heads[WINDOW] = len(prefix)
        self.assertEqual(heads, want_heads, machine.steps)
        for tape in p.scratch:
          self.assertTrue(all(v == BLANK for v in saved[tape].values()),
                          (machine.steps, tape))
        if machine.done:
          break
        machine.step()

  def test_completed_search_can_reuse_physical_scratch(self):
    p = make_cancellable(build_search_program("ab"))
    tapes = [{} for _ in range(p.ntapes)]
    heads = [0] * p.ntapes
    for prefix in ("a" * 50, "aabbbaaabbbaa", "b", "ab" * 17):
      tokens = [LEFT, *prefix, END]
      tokens[len(prefix)] = center_symbol(prefix[-1])
      tapes[WINDOW] = dict(enumerate(tokens))
      tapes[LOWER] = {0: LEFT, 1: END}
      heads[WINDOW] = len(prefix)
      result = p.execute(tapes, heads, 5000 * (len(prefix) + 1))
      self.assertEqual(result.state == p.found, expected(prefix, 0) is not None)
      p.execute(tapes, heads, 2000 * (len(prefix) + 1), start=p.cleanup)
      for tape in p.scratch:
        self.assertTrue(all(v == BLANK for v in tapes[tape].values()))


if __name__ == "__main__":
  unittest.main()
