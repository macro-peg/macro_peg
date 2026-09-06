"""Conservative local-head/queue service rates, not sampled maxima.

See GS_LOCAL_CLOCK.md. A logical head instruction uses at most one queue pop;
its three explicitly scheduled maintenance units give a factor of four.
The final PAL compiler still has to fold this whole fixed input round.
"""
from dataclasses import dataclass
from fractions import Fraction
from math import ceil


def power_two_at_least(value):
  return 1 << (max(1, value) - 1).bit_length()


@dataclass(frozen=True)
class Rates:
  k: int
  decomposition: int
  flag_view: int
  matching: int
  flags: int
  overhead: int = 39

  @property
  def round(self):
    return self.flags + self.overhead


def derive(k=8):
  if type(k) is not int or k < 4:
    raise ValueError("fixed integer k >= 4 required")
  failed = 44 * k + 31 + Fraction(13 * k, k - 2)
  final = 9 * k + 22 + Fraction(4, k)
  decomposition = ceil(final + failed * Fraction(k - 2, k * (k - 3))) + 5
  borders = (decomposition + (7 * k + 15) + (4 * k + 6) + 23) * Fraction(k - 1, k - 3)
  flag_view = ceil(borders + 6) + 5
  matching = 4 * power_two_at_least(decomposition + 32 * k + 16)
  # View length <=4K+1, K>=2: C(4K+1)+1 <= (4.5C+.5)K.
  # Four physical instructions per head instruction, over K/2 arrivals.
  flags = power_two_at_least(ceil(8 * (Fraction(9, 2) * flag_view + Fraction(1, 2))))
  if flags < matching:
    flags = matching
  return Rates(k, decomposition, flag_view, matching, flags)


DEFAULT = derive()


def derive_dual(k=8):
  """The two oriented views have length b each, without a 2b+1 view."""
  source = derive(k)
  flags = power_two_at_least(ceil(8 * (2 * source.flag_view + Fraction(1, 2))))
  return Rates(source.k, source.decomposition, source.flag_view, source.matching,
               max(flags, source.matching), source.overhead)


DEFAULT_DUAL = derive_dual()
