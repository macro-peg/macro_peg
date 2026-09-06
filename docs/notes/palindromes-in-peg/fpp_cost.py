"""Structural cost bounds for the finite FPP tables, in local instructions.

The control-graph factor is computed. The resource bounds are the symbolic
tape-motion ledger documented in FPP_COST.md, not fitted trace maxima. The
observer below makes each ledger obligation separately testable; it supplies
no runtime operation to the finite machine.
"""
from dataclasses import dataclass

from fpp_finite import A, B, C, S, T, BACK, FRONT, build_program


def successors(row):
  if row[0] == "read":
    return tuple(row[2].values())
  return () if row[0] == "halt" else (row[-1],)


def is_cut(row):
  return row[0] in ("move", "emit", "halt") or row[:2] == ("read", FRONT)


def cut_distance(program):
  """Maximum instructions through the next charged instruction, inclusive.

  Cutting moves, queue-front reads, outputs and halt makes the remaining
  graph acyclic. A non-cut cycle is an error, never a guessed finite bound.
  """
  visiting, distances = set(), {}

  def distance(q):
    if q in distances:
      return distances[q]
    if q in visiting:
      raise ValueError("uncharged cycle in finite FPP control graph")
    visiting.add(q)
    row = program.code[q]
    value = 1 if is_cut(row) else 1 + max(distance(k) for k in successors(row))
    visiting.remove(q)
    distances[q] = value
    return value

  return max(distance(q) for q in range(len(program.code)))


@dataclass(frozen=True)
class Affine:
  slope: int
  intercept: int = 0

  def __call__(self, size):
    if type(size) is not int or size < 0:
      raise ValueError("nonnegative integral size required")
    return self.slope * size + self.intercept


MOVE_BOUNDS = tuple(Affine(a, b) for a, b in (
  (4, 0), (1, 0), (6, 1), (7, 1), (2, 0), (4, 0), (4, 0)))
FRONT_READS = Affine(4)
EMITS = Affine(1)
PREPARATION = Affine(24, 42)
DP_SCAN = Affine(18, 25)


def bounds(alphabet="abs"):
  factor = cut_distance(build_program(alphabet + "#"))
  moves = Affine(sum(b.slope for b in MOVE_BOUNDS),
                 sum(b.intercept for b in MOVE_BOUNDS))
  cuts = Affine(moves.slope + FRONT_READS.slope + EMITS.slope,
                moves.intercept + 1)  # the final halt
  kernel = Affine(factor * cuts.slope, factor * cuts.intercept)
  # MARKS follows every A movement. Its emit-to-write replacement costs
  # exactly one instruction, just as the original emit does.
  marked_kernel = Affine(kernel.slope + MOVE_BOUNDS[A].slope, kernel.intercept)
  marked = Affine(2 * marked_kernel.slope + PREPARATION.slope,
                  marked_kernel.slope + marked_kernel.intercept + PREPARATION.intercept)
  # SECOND duplicates MARKS: setup is 3m+4, then A moves plus emits on
  # N=2m+1. The FPP halt is replaced by one read, with the same unit cost.
  duplicate = Affine(3 + 2 * (MOVE_BOUNDS[A].slope + EMITS.slope),
                     4 + MOVE_BOUNDS[A].slope + EMITS.slope)
  dp = Affine(marked.slope + duplicate.slope + DP_SCAN.slope,
              marked.intercept + duplicate.intercept + DP_SCAN.intercept)
  return dict(factor=factor, kernel=kernel, marked=marked, dp=dp)


class CostObservation:
  def __init__(self):
    self.moves = [0] * 7
    self.front_reads = self.emits = self.halts = self.steps = self.cuts = 0

  def observe(self, row):
    self.steps += 1
    self.cuts += int(is_cut(row))
    if row[0] == "move":
      self.moves[row[1]] += 1
    self.front_reads += int(row[:2] == ("read", FRONT))
    self.emits += int(row[0] == "emit")
    self.halts += int(row[0] == "halt")

  def check(self, size, factor):
    for tape, bound in enumerate(MOVE_BOUNDS):
      assert self.moves[tape] <= bound(size), (tape, size, self.moves, bound)
    assert self.front_reads <= FRONT_READS(size), (size, self.front_reads)
    assert self.emits <= EMITS(size) and self.halts == 1
    assert self.steps <= factor * self.cuts, (self.steps, factor, self.cuts)
