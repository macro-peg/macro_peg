"""Finite offline double-palindrome search; Galil's scheduler is separate.

Given ^word$ and unary ^1^lower$, prepare FPP marker tapes and find the
least h > lower with marked lengths 2h+1 and 4h+1. Output h is a unary
tape and success is a halt state. Addresses never guide machine control.
Input must be the reversed window when looking for suffix palindromes.
"""
from dataclasses import dataclass

from fpp_finite import Program, LEFT, END, BLANK
from fpp_subroutine import build_marked_program, SOURCE, MARKS

SECOND, LOWER, OUTPUT = 9, 10, 11


@dataclass
class DpRun:
  h: int | None
  steps: int
  tapes: tuple = ()
  positions: tuple = ()


class DpProgram(Program):
  def run(self, word, lower=0):
    if any(symbol not in self.source_alphabet for symbol in word):
      raise ValueError("input outside source alphabet")
    if type(lower) is not int or lower < 0:
      raise ValueError("nonnegative unary lower bound required")
    tapes = [{} for _ in range(self.ntapes)]
    tapes[SOURCE] = dict(enumerate(LEFT + word + END))
    tapes[LOWER] = dict(enumerate(LEFT + "1" * lower + END))
    result = self.execute(tapes, [0] * self.ntapes,
                          1000 * (len(word) + lower + 1))
    h = None
    if result.state == self.found:
      # Decode the unary OUTPUT tape externally, never a head position.
      h = 0
      while result.tapes[OUTPUT].get(h + 1, BLANK) == "1":
        h += 1
    return DpRun(h, result.steps, result.tapes, result.positions)


def build_dp_program(alphabet="ab"):
  fpp = build_marked_program(alphabet)
  p = DpProgram(alphabet + "#", ntapes=12)
  p.source_alphabet = tuple(alphabet)
  p.code = list(fpp.code)
  # The duplicate mark tape must remain coherent during preparation,
  # candidate movement, and marking. No shared tape or pointer equality.
  for q, row in enumerate(fpp.code):
    if row[0] in ("write", "move") and row[1] == MARKS:
      op, _, value, k = row
      extra = p.add((op, SECOND, value, k))
      p.code[q] = (op, MARKS, value, extra)

  p.found = p.add(("halt",))
  missed = p.add(("halt",))

  def advance(tape, count, k):
    # Check each skipped cell for the end marker; no out-of-bounds seek.
    for _ in range(count):
      move = p.move(tape, 1, k)
      k = p.branch(tape, {LEFT: move, "0": move, "1": move, END: missed})
    return k

  candidate = p.reserve()
  check_second = p.reserve()
  next_h = p.move(OUTPUT, 1, p.write(OUTPUT, "1",
           advance(MARKS, 2, advance(SECOND, 4, check_second))))
  check_first = p.branch(MARKS, {"0": next_h, "1": p.found, END: missed})
  allowed = p.branch(SECOND, {"0": next_h, "1": check_first, END: missed})
  p.branch(LOWER, {"1": p.move(LOWER, 1, next_h), END: allowed}, candidate)
  p.branch(SECOND, {"0": candidate, "1": candidate, END: missed}, check_second)
  begin = p.write(OUTPUT, LEFT, p.move(LOWER, 1,
          advance(MARKS, 1, advance(SECOND, 1, next_h))))
  for q, row in enumerate(fpp.code):
    if row[0] == "halt":
      # A local branch replaces the kernel's halt, with no epsilon callback.
      p.code[q] = ("read", SOURCE, {LEFT: begin})
  p.start = fpp.start
  p.validate()
  return p
