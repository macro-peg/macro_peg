"""Finite cleanup and cancellation entries for the FPP/DP fresh-tape kernels.

Requires their dense-prefix tape invariant, not arbitrary dirty tapes.
Cleanup destroys consumed/cancelled output, preserves input contents, and
returns every head to its origin. All work uses ordinary local instructions.
The finite cancel-entry dictionary is indexed by control state, not position.
Cancellation is allowed in the job/bootstrap, not during cleanup itself.
"""
import copy

from fpp_finite import Program, C, LEFT, BLANK, END
from fpp_subroutine import SOURCE
from dp_finite import LOWER

C_ORIGIN = "^0"


def make_reusable(program):
  if program.ntapes not in (9, 12):
    raise ValueError("expected a marked FPP or DP kernel")
  p = Program(program.alphabet, program.ntapes)
  p.source_alphabet = program.source_alphabet
  p.code = copy.deepcopy(program.code)
  if hasattr(program, "found"):
    p.found = program.found
  p.inputs = (SOURCE, LOWER) if p.ntapes == 12 else (SOURCE,)
  p.scratch = tuple(t for t in range(p.ntapes) if t not in p.inputs)

  # The first C seed write is reached through a fixed, write-only startup.
  # Tag that origin so cleanup can find it by a finite symbol test. Later
  # C writes only materialize newly visited blank cells beyond the seed.
  q = program.start
  seen = set()
  while True:
    if q in seen:
      raise ValueError("unexpected startup cycle")
    seen.add(q)
    row = p.code[q]
    if tuple(row[:3]) == ("write", C, "0"):
      p.code[q] = ("write", C, C_ORIGIN, row[3])
      break
    if row[0] != "write":
      raise ValueError("unexpected pre-seed startup instruction")
    q = row[3]
  for q, row in enumerate(p.code):
    if row[0] == "read" and row[1] == C and "0" in row[2]:
      p.code[q] = ("read", C, {**row[2], C_ORIGIN: row[2]["0"]})

  done = p.add(("halt",))
  body_symbols = set(program.alphabet) | {END, BLANK, "0", "1"}

  def reset_tape(tape, erase, k):
    root = C_ORIGIN if tape == C else LEFT
    seek = p.reserve()
    if erase:
      clear, back = p.reserve(), p.reserve()
      p.branch(tape, {root: p.write(tape, BLANK, k),
                      BLANK: p.move(tape, -1, back)}, back)
      p.branch(tape, {BLANK: p.move(tape, -1, back), **{
        symbol: p.write(tape, BLANK, p.move(tape, 1, clear))
        for symbol in body_symbols if symbol != BLANK}}, clear)
      at_root = p.move(tape, 1, clear)
    else:
      at_root = k
    p.branch(tape, {root: at_root, **{
      symbol: p.move(tape, -1, seek) for symbol in body_symbols}}, seek)
    return seek

  cleanup = done
  for tape in reversed(range(p.ntapes)):
    cleanup = reset_tape(tape, tape in p.scratch, cleanup)
  p.cleanup = cleanup

  def bootstrap(k):
    states = []
    for tape in reversed(p.scratch):
      k = p.write(tape, C_ORIGIN if tape == C else LEFT, k)
      states.append(k)
    return k, states

  # No bootstrap move occurs. Even a partly bootstrapped job can safely
  # finish writing all origin markers, then enter the uniform cleanup.
  cancel_bootstrap, _ = bootstrap(cleanup)
  p.start, bootstrap_states = bootstrap(program.start)
  p.cancel_entries = {q: cleanup for q in range(len(program.code))}
  p.cancel_entries.update({q: cancel_bootstrap for q in bootstrap_states})
  p.validate()
  return p
