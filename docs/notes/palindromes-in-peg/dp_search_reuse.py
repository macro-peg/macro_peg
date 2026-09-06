"""Cancellation/reentry for the whole offline doubling search.

One extra unary tape tracks WINDOW's distance from C and furthest visit.
Finite cancellation entries finish a half-completed paired move before
cleanup. Cancel any normal instruction, including internal kernel cleanup,
but not the outer cleanup itself. WINDOW/LOWER survive; all scratch clears.
"""
import copy

from fpp_finite import Program, LEFT, END, BLANK, C
from fpp_reuse import C_ORIGIN
from dp_finite import LOWER
from dp_search_finite import WINDOW, STATUS, period_symbol

ERASED = "erased:_"


def make_cancellable(search):
  if search.ntapes < 16:
    raise ValueError("expected the local doubling search or its chain monitor")
  distance = search.ntapes
  p = Program(search.alphabet, ntapes=search.ntapes + 1)
  p.distance = distance
  p.scalar_tapes = tuple(getattr(search, "scalar_tapes", (STATUS,)))
  p.source_alphabet = search.source_alphabet
  p.code = copy.deepcopy(search.code)
  p.found, p.missed = search.found, search.missed
  for name in ("outcomes", "ready", "confirmed_ready"):
    if hasattr(search, name):
      setattr(p, name, copy.deepcopy(getattr(search, name)))
  p.scratch = tuple(t for t in range(p.ntapes) if t not in (WINDOW, LOWER))

  # Retain origins during INTERNAL cleanup so a cancellation can locate
  # them even halfway through that sweep. The next kernel bootstrap
  # overwrites these same origin cells; its body still starts blank.
  for row in p.code:
    if row[0] != "read":
      continue
    tape = row[1]
    root = C_ORIGIN if tape == C else LEFT
    target = row[2].get(root)
    if target is not None and tuple(p.code[target][:3]) == ("write", tape, BLANK):
      p.code[target] = ("write", tape, root, p.code[target][3])

  # Logical deletion leaves a physical tombstone. Internal left-to-right
  # cleanup can otherwise leave a blank hole before still-live cells.
  # Normal code treats the tombstone exactly as blank; outer cleanup sees
  # a dense physical prefix and sweeps through it to the true frontier.
  for q, row in enumerate(p.code):
    if row[0] == "write" and row[2] == BLANK:
      p.code[q] = ("write", row[1], ERASED, row[3])
    elif row[0] == "read" and BLANK in row[2]:
      p.code[q] = ("read", row[1], {**row[2], ERASED: row[2][BLANK]})

  pending = {}
  for q, row in enumerate(search.code):
    if row[0] == "move" and row[1] == WINDOW:
      direction, k = row[2:]
      if direction == -1:
        written = p.write(distance, "1", k)
        pending[written] = "write"
        k = written
      moved = p.move(distance, -direction, k)
      pending[moved] = -direction
      p.code[q] = ("move", WINDOW, direction, moved)

  done = p.add(("halt",))
  symbols = [set() for _ in range(p.ntapes)]
  for row in p.code:
    if row[0] == "read":
      symbols[row[1]].update(row[2])
    elif row[0] == "write":
      symbols[row[1]].add(row[2])

  def reset(tape, k):
    root = C_ORIGIN if tape == C else LEFT
    body = sorted((symbols[tape] | {BLANK}) - {root})
    seek, clear, back = p.reserve(), p.reserve(), p.reserve()
    p.branch(tape, {root: p.write(tape, BLANK, k),
                    BLANK: p.move(tape, -1, back)}, back)
    p.branch(tape, {BLANK: p.move(tape, -1, back), **{
      s: p.write(tape, BLANK, p.move(tape, 1, clear))
      for s in body if s != BLANK}}, clear)
    p.branch(tape, {root: p.move(tape, 1, clear), **{
      s: p.move(tape, -1, seek) for s in body}}, seek)
    return seek

  clean_scratch = done
  for tape in reversed(p.scratch):
    if tape in p.scalar_tapes:
      clean_scratch = p.write(tape, BLANK, clean_scratch)
    else:
      clean_scratch = reset(tape, clean_scratch)
  lower = p.reserve()
  p.branch(LOWER, {LEFT: clean_scratch, **{
    s: p.move(LOWER, -1, lower) for s in ("1", END)}}, lower)

  # First return to C using the aligned distance heads. Sweep precisely
  # the already visited prefix, erasing P tags, then return to C again.
  # The retained unary extent makes marks behind the current head visible.
  home, scan, restore = p.reserve(), p.reserve(), p.reserve()
  p.branch(distance, {LEFT: lower,
    "1": p.move(WINDOW, 1, p.move(distance, -1, restore))}, restore)
  next_cell = p.move(distance, 1, scan)
  unmark = p.branch(WINDOW, {LEFT: next_cell, **{
    s: next_cell for s in p.source_alphabet}, **{
    period_symbol(s): p.write(WINDOW, s, next_cell) for s in p.source_alphabet}})
  p.branch(distance, {BLANK: p.move(distance, -1, restore),
    "1": p.move(WINDOW, -1, unmark)}, scan)
  p.branch(distance, {LEFT: next_cell,
    "1": p.move(WINDOW, 1, p.move(distance, -1, home))}, home)
  p.cleanup = home

  p.cancel_entries = {q: home for q in range(len(search.code))}
  for q, action in pending.items():
    if action == "write":
      entry = p.write(distance, "1", home)
    elif action == 1:
      entry = p.move(distance, 1, p.write(distance, "1", home))
    else:
      entry = p.move(distance, -1, home)
    p.cancel_entries[q] = entry

  def bootstrap(k):
    states = []
    for tape in reversed(p.scratch):
      symbol = ("N" if tape == STATUS else BLANK if tape in p.scalar_tapes
                else C_ORIGIN if tape == C else LEFT)
      k = p.write(tape, symbol, k)
      states.append(k)
    return k, states

  cancel_bootstrap, _ = bootstrap(home)
  p.start, states = bootstrap(search.start)
  p.cancel_entries.update({q: cancel_bootstrap for q in states})
  p.validate()
  return p
