"""Local doubling-window search for Galil's dp(C,r), without its scheduler.

WINDOW contains ^prefix suffix$, with the last prefix cell center-tagged;
its head starts at that tag. LOWER is ^1^r$, head at its left marker.
All other tapes start blank at zero. Only finite read/write/unit-move
instructions execute. Success marks C-h, C-2h, C-3h, C-4h and returns h
in unary. Both success and failure restore WINDOW's head to C.

This is an offline search component. There are no match-paced waiting
barriers, outer cancellation entries, or right-chain confirmation yet.
"""
import copy

from fpp_finite import Program, LEFT, END, BLANK
from fpp_subroutine import SOURCE
from dp_finite import build_dp_program, LOWER, OUTPUT
from fpp_reuse import make_reusable

WINDOW, SPAN, TEMP, STATUS = 12, 13, 14, 15


def center_symbol(symbol):
  return "C:" + symbol


def period_symbol(symbol):
  return "P:" + symbol


def build_search_program(alphabet="abs"):
  kernel = build_dp_program(alphabet)
  reusable = make_reusable(kernel)
  p = Program(alphabet + "#", ntapes=16)
  p.source_alphabet = tuple(alphabet)
  p.code = copy.deepcopy(reusable.code)
  p.found, p.missed = p.add(("halt",)), p.add(("halt",))

  def rewind(tape, symbols, k):
    q = p.reserve()
    p.branch(tape, {LEFT: k, **{
      s: p.move(tape, -1, q) for s in symbols}}, q)
    return q

  def center(k):
    q = p.reserve()
    p.branch(WINDOW, {**{center_symbol(s): k for s in alphabet}, **{
      s: p.move(WINDOW, 1, q)
      for s in (LEFT, *alphabet, *(period_symbol(a) for a in alphabet))}}, q)
    return q

  def push(tape, symbol, k):
    return p.move(tape, 1, p.write(tape, symbol, k))

  # Consume the unary answer four times to place the four semiperiod marks.
  # Rewinding OUTPUT is local; h is never decoded by runtime control.
  mark_next = center(p.found)
  for _ in range(4):
    walk = p.reserve()
    mark = p.branch(WINDOW, {
      s: p.write(WINDOW, period_symbol(s),
                 rewind(OUTPUT, ("1", BLANK), mark_next)) for s in alphabet})
    p.branch(OUTPUT, {BLANK: mark,
      "1": p.move(WINDOW, -1, p.move(OUTPUT, 1, walk))}, walk)
    mark_next = p.move(OUTPUT, 1, walk)
  mark_start = rewind(OUTPUT, ("1", BLANK), mark_next)
  p.code[reusable.found] = ("read", STATUS, {"N": mark_start, "F": mark_start})

  stage = p.reserve()
  # At stage entry all twelve kernel heads are at zero, scratch is blank,
  # WINDOW scans C, SPAN holds 1^ell, and TEMP is empty apart from its root.
  copied = p.reserve()
  copy_symbol = p.reserve()
  ready = rewind(SOURCE, (*alphabet, END),
          rewind(SPAN, ("1", BLANK), center(reusable.start)))
  finish = p.write(SOURCE, END, ready)
  final = p.write(STATUS, "F", finish)
  check_budget = p.branch(SPAN, {
    "1": p.move(SPAN, 1, copy_symbol), BLANK: finish})
  left_probe = p.branch(WINDOW, {LEFT: final, **{
    s: check_budget for s in alphabet}})
  p.branch(SOURCE, {s: p.move(SOURCE, 1,
                   p.move(WINDOW, -1, left_probe)) for s in alphabet}, copied)
  p.branch(WINDOW, {
    token: p.write(SOURCE, s, copied)
    for s in alphabet for token in (s, center_symbol(s))}, copy_symbol)
  start_copy = p.write(SOURCE, LEFT, p.move(SOURCE, 1,
               p.move(SPAN, 1, copy_symbol)))
  p.branch(STATUS, {"N": start_copy, "F": start_copy}, stage)

  # Double SPAN through a distinct unary temporary tape. Each bit is read,
  # erased, and replaced through local moves; no growing integer register.
  double, back, transfer = p.reserve(), p.reserve(), p.reserve()
  resume = rewind(SPAN, ("1",), p.write(STATUS, "N", stage))
  p.branch(TEMP, {LEFT: resume, "1": p.write(TEMP, BLANK,
    p.move(TEMP, -1, push(SPAN, "1", transfer)))}, transfer)
  p.branch(SPAN, {LEFT: transfer,
    BLANK: p.move(SPAN, -1, back),
    "1": p.write(SPAN, BLANK, p.move(SPAN, -1, back))}, back)
  p.branch(SPAN, {BLANK: back,
    "1": push(TEMP, "1", push(TEMP, "1", p.move(SPAN, 1, double)))}, double)
  next_stage = p.move(SPAN, 1, double)

  # Kernel miss first clears its own work tapes and rewinds LOWER. Clear
  # the old SOURCE copy as well before building the next, larger window.
  clear, return_source = p.reserve(), p.reserve()
  dispatch = p.branch(STATUS, {"F": p.missed, "N": next_stage})
  p.branch(SOURCE, {LEFT: dispatch,
                    BLANK: p.move(SOURCE, -1, return_source)}, return_source)
  p.branch(SOURCE, {BLANK: p.move(SOURCE, -1, return_source), **{
    s: p.write(SOURCE, BLANK, p.move(SOURCE, 1, clear))
    for s in (*alphabet, END)}}, clear)
  cleanup_halts = [q for q in range(len(kernel.code), len(reusable.code))
                   if p.code[q][0] == "halt"]
  assert len(cleanup_halts) == 1
  p.code[cleanup_halts[0]] = ("move", SOURCE, 1, clear)
  for q, row in enumerate(kernel.code):
    if row[0] == "halt" and q != kernel.found:
      p.code[q] = ("read", STATUS, {"N": reusable.cleanup, "F": reusable.cleanup})

  # Initial ell = 8*max(r,1), as in the current behavioral reference.
  # The eight copies per bit are compile-time unrolling, not runtime loops.
  initialize = p.reserve()
  after_lower = rewind(LOWER, ("1", END), rewind(SPAN, ("1",), stage))
  eight_default = after_lower
  for _ in range(8):
    eight_default = push(SPAN, "1", eight_default)
  nonempty = p.branch(SPAN, {LEFT: eight_default, "1": after_lower})
  eight = p.move(LOWER, 1, initialize)
  for _ in range(8):
    eight = push(SPAN, "1", eight)
  p.branch(LOWER, {"1": eight, END: nonempty}, initialize)
  p.start = p.write(STATUS, "N", p.write(TEMP, LEFT,
            p.write(SPAN, LEFT, p.move(LOWER, 1, initialize))))
  p.validate()
  return p
