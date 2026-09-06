"""Finite right-dp / three-way chain monitor, composed after local DP search.

The caller supplies right-side places C+1,C+2,... through a one-cell PORT,
only at a ready state. Setup is offline. Each subsequent place takes
constant local work: PERIOD bounces along a marked semiperiod, while
WINDOW moves left for ordinary palindrome matching. This implements the
branch decisions, not the outer center shift/restart/move scheduler.
"""
import copy

from fpp_finite import Program, LEFT, END, BLANK
from dp_search_finite import build_search_program, WINDOW, STATUS, center_symbol, period_symbol

PERIOD, PORT = 16, 17


def first(symbol):
  return "F:" + symbol


def last(symbol):
  return "T:" + symbol


def build_chain_program(alphabet="abs"):
  search = build_search_program(alphabet)
  p = Program(search.alphabet, ntapes=18)
  p.source_alphabet = search.source_alphabet
  p.scalar_tapes = (STATUS, PORT)
  p.code = copy.deepcopy(search.code)
  p.outcomes = {search.missed: "no_chain"}
  for name in ("palindrome", "failed_right_dp", "restart_search", "chain_shift",
               "nonchain_move", "end_chain", "end_pending"):
    state = p.add(("halt",))
    setattr(p, name, state)
    p.outcomes[state] = name
  p.no_chain = search.missed
  # These remain available to composition clients expecting search labels.
  p.found, p.missed = search.found, search.missed
  ready = {(phase, direction): p.reserve()
           for phase in range(5) for direction in (-1, 1)}
  p.ready = set(ready.values())
  p.confirmed_ready = {q for (phase, _), q in ready.items() if phase == 4}
  letters = {token: s for s in alphabet
             for token in (s, center_symbol(s), period_symbol(s))}
  period_letters = {token: s for s in alphabet for token in (s, first(s), last(s))}

  for (phase, direction), q in ready.items():
    choices = {BLANK: q, END: p.write(PORT, BLANK,
               p.end_chain if phase == 4 else p.end_pending)}
    for right in alphabet:
      compare_period = {}
      for token, prediction in period_letters.items():
        if token.startswith("F:"):
          next_direction, next_phase = 1, min(4, phase + 1)
        elif token.startswith("T:"):
          next_direction, next_phase = -1, min(4, phase + 1)
        else:
          next_direction, next_phase = direction, phase
        advance = p.move(PERIOD, next_direction, ready[next_phase, next_direction])
        boundary = p.branch(WINDOW, {LEFT: p.palindrome, **{
          symbol: advance for symbol in letters}})
        matching = p.move(WINDOW, -1, boundary)
        compare_left = {}
        for symbol, left in letters.items():
          if left == right == prediction:
            target = matching
          elif phase < 4:
            target = p.failed_right_dp
          elif left == right:
            target = p.restart_search
          elif prediction == right:
            target = p.chain_shift
          else:
            target = p.nonchain_move
          compare_left[symbol] = p.write(PORT, BLANK, target)
        compare_period[token] = p.branch(WINDOW, compare_left)
      choices[right] = p.branch(PERIOD, compare_period)
    p.branch(PORT, choices, q)

  # Read the first marked semiperiod directly from the DP result: C..C-h.
  # A local bouncing reader avoids O(h) rewinds between incoming places.
  back_period, back_window, copy_period = p.reserve(), p.reserve(), p.reserve()
  start_matching = p.move(WINDOW, -1, p.move(PERIOD, 1, ready[0, 1]))
  p.branch(WINDOW, {**{center_symbol(s): start_matching for s in alphabet}, **{
    token: p.move(WINDOW, 1, back_window)
    for s in alphabet for token in (s, period_symbol(s))}}, back_window)
  p.branch(PERIOD, {LEFT: p.move(PERIOD, 1, back_window), **{
    token: p.move(PERIOD, -1, back_period) for token in period_letters}}, back_period)
  p.branch(WINDOW, {**{
    period_symbol(s): p.write(PERIOD, last(s), back_period) for s in alphabet}, **{
    s: p.write(PERIOD, s, p.move(PERIOD, 1,
       p.move(WINDOW, -1, copy_period))) for s in alphabet}}, copy_period)
  initial_symbol = p.branch(WINDOW, {
    center_symbol(s): p.write(PERIOD, first(s), p.move(PERIOD, 1,
      p.move(WINDOW, -1, copy_period))) for s in alphabet})
  p.code[search.found] = ("write", PERIOD, LEFT, p.move(PERIOD, 1, initial_symbol))
  p.start = p.write(PORT, BLANK, search.start)
  p.validate()
  return p
