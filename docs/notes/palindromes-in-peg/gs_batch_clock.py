"""Service bounds for the window backend's fixed GS batch instructions.

The batch movement operands are constants in the finite instruction table.
See GS_LOCAL_CLOCK.md for their loop accounting. Queue maintenance is done
once while preparing each actual input-node window, outside this service.
"""
from dataclasses import dataclass
from fractions import Fraction
from math import ceil

from gs_local_clock import derive, power_two_at_least


@dataclass(frozen=True)
class BatchRates:
  k: int
  decomposition: int
  single_stage: int
  first_job: int
  matching: int
  flags: int


def derive_batch(k=8):
  if type(k) is not int or k < 4:
    raise ValueError("fixed integer k >= 4 required")
  failed = 34 * k + 32 + Fraction(10 * k + 2, k - 2)
  final = 7 * k + 19 + Fraction(5, k)
  decomposition = ceil(final + failed * Fraction(k - 2, k * (k - 3))) + 5
  stage = decomposition + (5 * k + 12) + (3 * k + 6) + 23
  single_stage = stage + 11
  first_job = ceil(stage * Fraction(k - 1, k - 3)) + 11
  # For jobs 2..4, Lower >= b/2, whereas the next stage is <2b/(k-1).
  # With k=8 it is already below Lower. Job 1 still sums all stages.
  if Fraction(2, k - 1) >= Fraction(1, 2):
    raise ValueError("this early-stop job schedule requires k >= 6")
  flag_rate = power_two_at_least(max(first_job, 4 * single_stage + 1))
  # A batch costs no more instructions than its unit expansion; retain the
  # already established logical matcher rate instead of tightening it here.
  matching = derive(k).matching // 4
  return BatchRates(k, decomposition, single_stage, first_job, matching, max(matching, flag_rate))


DEFAULT_BATCH = derive_batch()
