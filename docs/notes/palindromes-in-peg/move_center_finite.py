"""Offline local center selection for the nonchain move, not the full move.

WINDOW has a nonempty interval with ML/MR (or MLR) boundary tags; its head
starts at the right boundary. Interior C/P annotations may be present.
All scratch starts blank at zero. The controller copies the reversed
interval locally, runs FPP, selects its longest odd palindrome prefix and
moves WINDOW to the corresponding suffix center. It leaves C and RR tags,
preserving the underlying text and cells outside the interval.

No coordinate arithmetic remains in the finite instruction table. Scratch
is erased and its heads restored to zero before return, permitting reuse.
Preparing boundaries, multihead repositioning and main1 replay remain outer
operations. Mid-procedure cancellation is not provided.
"""
from fpp_finite import Program, LEFT, END, BLANK
from fpp_subroutine import build_marked_program, SOURCE, MARKS
from fpp_reuse import make_reusable
from dp_search_finite import WINDOW

FIRST = "first:1"


def boundary(symbol, left=False, right=False):
  if not left and not right:
    raise ValueError("at least one boundary required")
  return ("MLR:" if left and right else "ML:" if left else "MR:") + symbol


def decode(symbol):
  # Every source symbol is one character, including ':' itself.
  return symbol[-1]


def build_move_center_program(alphabet="abs"):
  base_kernel = build_marked_program(alphabet)
  kernel = make_reusable(base_kernel)
  p = Program(kernel.alphabet, ntapes=WINDOW + 1)
  p.source_alphabet = tuple(alphabet)
  p.code = list(kernel.code)
  p.finished = p.add(("halt",))
  # Kernel cleanup preserves SOURCE; erase that local copy too, leaving
  # only the annotation changes on WINDOW as externally visible output.
  clear_source, back_source = p.reserve(), p.reserve()
  p.branch(SOURCE, {LEFT: p.write(SOURCE, BLANK, p.finished),
    BLANK: p.move(SOURCE, -1, back_source)}, back_source)
  p.branch(SOURCE, {BLANK: p.move(SOURCE, -1, back_source), **{
    s: p.write(SOURCE, BLANK, p.move(SOURCE, 1, clear_source))
    for s in (*alphabet, END)}}, clear_source)
  for q in range(len(base_kernel.code), len(kernel.code)):
    if kernel.code[q][0] == "halt":
      p.code[q] = ("move", SOURCE, 1, clear_source)
  mark_center = p.branch(WINDOW, {
    **{s: p.write(WINDOW, "C:" + s, kernel.cleanup) for s in alphabet},
    **{"RR:" + s: p.write(WINDOW, "CRR:" + s, kernel.cleanup) for s in alphabet}})

  # At the chosen odd prefix length ell, two unit MARKS moves correspond
  # to one unit WINDOW move. FIRST identifies ell=1 without an address test.
  reduce = p.reserve()
  shorter = p.move(MARKS, -1, p.move(MARKS, -1, p.move(WINDOW, -1, reduce)))
  p.branch(MARKS, {FIRST: p.write(MARKS, "1", mark_center),
                  "0": shorter, "1": shorter}, reduce)
  forward = [p.reserve(), p.reserve()]
  backward = [p.reserve(), p.reserve()]
  for parity in (0, 1):
    before = p.move(MARKS, -1, backward[1 - parity])
    choices = {"0": before, "1": reduce if parity == 1 else before}
    if parity == 1:
      choices[FIRST] = reduce
    p.branch(MARKS, choices, backward[parity])
    after = p.move(MARKS, 1, forward[1 - parity])
    p.branch(MARKS, {"0": after, "1": after, END: before}, forward[parity])
  inspect = p.move(MARKS, 1, p.write(MARKS, FIRST,
            p.move(MARKS, 1, forward[0])))
  for q, row in enumerate(base_kernel.code):
    if row[0] == "halt":
      p.code[q] = ("read", MARKS, {LEFT: inspect})

  # FPP requires SOURCE at its origin; WINDOW remains at RR while it runs.
  rewind_source = p.reserve()
  p.branch(SOURCE, {LEFT: kernel.start, **{
    s: p.move(SOURCE, -1, rewind_source) for s in (*alphabet, END)}}, rewind_source)
  restore_window = p.reserve()
  ordinary = {tag + s: s for s in alphabet
              for tag in ("", "C:", "P:", "RR:", "CRR:", "ML:")}
  right = {tag + s: s for s in alphabet for tag in ("MR:", "MLR:")}
  p.branch(WINDOW, {
    **{token: p.write(WINDOW, s, p.move(WINDOW, 1, restore_window))
       for token, s in ordinary.items()},
    **{token: p.write(WINDOW, "RR:" + s, rewind_source)
       for token, s in right.items()}}, restore_window)

  copy = p.reserve()
  finish_copy = p.write(SOURCE, END, restore_window)
  letters = {tag + s: s for s in alphabet
             for tag in ("", "C:", "P:", "RR:", "CRR:", "ML:", "MR:", "MLR:")}
  p.branch(WINDOW, {token: p.write(SOURCE, s, p.move(SOURCE, 1,
    finish_copy if token.startswith(("ML:", "MLR:")) else
    p.move(WINDOW, -1, copy))) for token, s in letters.items()}, copy)
  p.start = p.write(SOURCE, LEFT, p.move(SOURCE, 1, copy))
  p.validate()
  return p
