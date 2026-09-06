"""Clock bounds derived from the finite program and source control structure.

The instruction quantum is a compilation granularity, not an input budget.
Every positive quantum has a derived match interval. Predictability constants
are conditional on the source contracts documented in GALIL_CLOCK.md.
"""
from dataclasses import dataclass
from fractions import Fraction
from math import ceil

from fpp_cost import bounds


@dataclass(frozen=True)
class Timing:
  quantum: int
  stage_factor: int
  match_delay: int
  move_slope: int
  interval_overhead: int
  predictability: int

  @property
  def service(self):
    return 2 * self.predictability


def derive(quantum=64):
  if type(quantum) is not int or quantum < 1:
    raise ValueError("positive integral instruction quantum required")
  cost = bounds()
  a, b = cost["dp"].slope, cost["dp"].intercept
  # First span is 8*max(r,1); subsequent spans double and are at least 16.
  first = Fraction(19, 8) + Fraction(a, quantum) + Fraction(10, 8) + Fraction(a + b, 8 * quantum)
  later = Fraction(11, 4) + Fraction(a, quantum) + Fraction(10, 16) + Fraction(a + b, 16 * quantum)
  stage = ceil(max(first, later))
  # Initial main slack is span/24. Eight also suffices to finish chain
  # copying/catch-up before the symmetric radius reaches 4h.
  minimum = max(24 * stage, 8)
  delay = 1 << (minimum - 1).bit_length()
  # A move of delta has old radius <=4*delta and copied length <=8*delta.
  # Allow two match intervals per replayed place, including chain restart.
  move = 8 * delay + 40 + ceil(Fraction(8 * cost["marked"].slope, quantum))
  fallback_overhead = 6 + ceil(Fraction(cost["marked"].intercept, quantum))
  overhead = 4 * delay + 2 * fallback_overhead + 2
  return Timing(quantum, stage, delay, move, overhead, move + overhead)
