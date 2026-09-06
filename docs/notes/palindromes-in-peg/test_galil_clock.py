"""Check integer deadline margins, including the zero-radius initial call."""
from math import ceil
import unittest

from fpp_cost import bounds
from galil_clock import derive


class GalilClockTest(unittest.TestCase):
  def test_stage_cost_fits_initial_and_subsequent_match_deadlines(self):
    dp = bounds()["dp"]
    for quantum in (1, 2, 3, 8, 64, 128, 4096):
      timing = derive(quantum)
      for lower in range(65):
        span = 8 * max(lower, 1)
        for stage in range(6):
          size = span + 1
          growth = max(lower, 1) if stage == 0 else span // 2
          work = growth + 2 * lower + 2 * size + 7 + (dp(size) + quantum - 1) // quantum
          self.assertLessEqual(work, timing.stage_factor * span, (quantum, lower, stage))
          if stage == 0:
            radius = (5 * lower) // 3
            comparisons = work // timing.match_delay  # freshly reset clock
          else:
            radius = span // 8
            comparisons = (work + timing.match_delay - 1) // timing.match_delay
          self.assertLessEqual(radius + comparisons, span // 4, (quantum, lower, stage))
          span *= 2

  def test_predictability_arithmetic_covers_positive_and_negative_boundaries(self):
    timing = derive()
    for size in range(1, 50):
      for old_center in range(size, 2 * size):
        for new_center in range(max(old_center, size + 1), 2 * (size + 1)):
          delta = new_center - old_center
          old_k = max(old_center - size - 1, 0)
          new_k = max(new_center - size - 2, 0)
          cost = timing.move_slope * delta + timing.interval_overhead
          self.assertGreaterEqual((new_k - old_k + 2) * timing.predictability, cost)
          if new_center == size + 1:
            self.assertLessEqual(cost, timing.predictability)


if __name__ == "__main__":
  unittest.main()
