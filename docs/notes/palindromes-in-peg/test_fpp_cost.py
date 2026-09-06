"""Check symbolic resource bounds on finite-table executions."""
from itertools import product
import unittest

from fpp_finite import build_program, LEFT, END
from fpp_subroutine import build_marked_program, SOURCE
from dp_finite import build_dp_program, LOWER
from fpp_cost import CostObservation, bounds, cut_distance, PREPARATION, DP_SCAN


class FppCostTest(unittest.TestCase):
  def test_control_graph_and_each_resource(self):
    p = build_program("abs#")
    factor = cut_distance(p)
    self.assertEqual(factor, 4)
    words = ["".join(chars) for n in range(8) for chars in product("ab", repeat=n)]
    words += ["a" * 512, "a" * 512 + "b", "ab" * 256,
              "b" * 128 + "a" + "b" * 64 + "aa" + "b" * 128]
    for word in words:
      z = dict(enumerate(LEFT + word + END))
      e = p.execution([z, dict(z), {0: "0", 1: "1", 2: "0"},
                       {0: LEFT}, {0: LEFT}, {0: LEFT}, {0: LEFT}],
                      [0, 1, 0, 0, 0, 0, 0])
      seen = CostObservation()
      while not e.done:
        seen.observe(p.code[e.state])
        e.step()
      seen.check(len(word), factor)

  def test_analyzer_rejects_an_uncharged_cycle(self):
    p = build_program("ab#")
    p.code[p.start] = ("write", 0, LEFT, p.start)
    with self.assertRaisesRegex(ValueError, "uncharged cycle"):
      cut_distance(p)

  def test_marked_setup_and_dp_scan_costs(self):
    kernel = build_program("abs#")
    marked, dp = build_marked_program("abs"), build_dp_program("abs")
    limits = bounds()
    kernel_halts = {q for q, row in enumerate(kernel.code) if row[0] == "halt"}
    for n in range(7):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        tapes = [{} for _ in range(marked.ntapes)]
        tapes[SOURCE] = dict(enumerate(LEFT + word + END))
        e = marked.execution(tapes, [0] * marked.ntapes)
        while e.state != kernel.start:
          e.step()
        self.assertEqual(e.steps, PREPARATION(n), word)
        while not e.done:
          e.step()
        self.assertLessEqual(e.steps, limits["marked"](n), word)
        for lower in (0, n, n + 3):
          tapes = [{} for _ in range(dp.ntapes)]
          tapes[SOURCE] = dict(enumerate(LEFT + word + END))
          tapes[LOWER] = dict(enumerate(LEFT + "1" * lower + END))
          e = dp.execution(tapes, [0] * dp.ntapes)
          while e.state not in kernel_halts:
            e.step()
          # This state replaces the FPP halt by one read. Charge that
          # read to the kernel, so the remaining scan excludes it.
          e.step()
          scan_start = e.steps
          while not e.done:
            e.step()
          self.assertLessEqual(e.steps - scan_start, DP_SCAN(n), (word, lower))
          self.assertLessEqual(e.steps, limits["dp"](n), (word, lower))


if __name__ == "__main__":
  unittest.main()
