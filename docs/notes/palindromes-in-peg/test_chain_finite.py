"""Right-dp confirmation and three-way chain matching from real DP output."""
from itertools import product
import copy
import json
from pathlib import Path
import unittest

from fpp_finite import Program, LEFT, END, BLANK
from dp_finite import LOWER
from dp_search_finite import WINDOW, center_symbol
from test_dp_search_finite import expected
import chain_finite as chain
from dp_search_reuse import make_cancellable


def reference(prefix, right, lower):
  h = expected(prefix, lower)
  if h is None:
    return "no_chain", 0
  for j, symbol in enumerate(right, 1):
    left = prefix[-j-1]
    phase = j % (2*h)
    prediction = prefix[-min(phase, 2*h-phase)-1]
    if left == symbol == prediction:
      if j == len(prefix) - 1:
        return "palindrome", j
      continue
    if j <= 4*h:
      return "failed_right_dp", j
    if left == symbol:
      return "restart_search", j
    if prediction == symbol:
      return "chain_shift", j
    return "nonchain_move", j
  return "end_chain" if len(right) >= 4*h else "end_pending", len(right)


def initial(p, prefix, lower):
  tapes = [{} for _ in range(p.ntapes)]
  tokens = [LEFT, *prefix, END]
  tokens[len(prefix)] = center_symbol(prefix[-1])
  tapes[WINDOW] = dict(enumerate(tokens))
  tapes[LOWER] = dict(enumerate(LEFT + "1" * lower + END))
  heads = [0] * p.ntapes
  heads[WINDOW] = len(prefix)
  return tapes, heads


def run(p, prefix, right, lower=0):
  tapes, heads = initial(p, prefix, lower)
  machine = p.execution(tapes, heads)
  cap = 5000 * (len(prefix) + lower + 1)
  while machine.state not in p.ready and not machine.done:
    assert machine.steps < cap
    machine.step()
  consumed = 0
  costs = []
  if machine.done:
    return p.outcomes[machine.state], consumed, costs
  for symbol in (*right, END):
    tapes[chain.PORT][0] = symbol
    before = machine.steps
    machine.step()
    while machine.state not in p.ready and not machine.done:
      assert machine.steps - before < 30
      machine.step()
    costs.append(machine.steps - before)
    if symbol != END:
      consumed += 1
    if machine.done:
      return p.outcomes[machine.state], consumed, costs
  raise AssertionError("did not halt at stream end")


class ChainTest(unittest.TestCase):
  def test_saved_monitor_without_builder(self):
    path = Path(__file__).parent / "generated" / "chain-monitor-controller.json"
    data = json.loads(path.read_text())
    p = Program(data["alphabet"], data["ntapes"])
    p.code, p.start = data["code"], data["start"]
    p.ready = set(data["ready"])
    p.outcomes = {int(q): name for q, name in data["outcomes"].items()}
    p.validate()
    for prefix, right in [("a" * 15, "a" * 14),
                          ("b" + "a" * 14, "a" * 14),
                          ("ab" * 10, "babaabab"), ("ab", "")]:
      got, used, _ = run(p, prefix, right)
      self.assertEqual((got, used), reference(prefix, right, 0))

  def test_cancellation_during_setup_and_stream_monitor(self):
    p = make_cancellable(chain.build_chain_program("ab"))
    prefix = "b" + "a" * 14
    tapes, heads = initial(p, prefix, 0)
    original_window = dict(tapes[WINDOW])
    original_lower = dict(tapes[LOWER])
    machine = p.execution(tapes, heads)
    remaining = iter("a" * 14 + END)
    while True:
      saved, positions = copy.deepcopy(tapes), list(heads)
      p.execute(saved, positions, 30000, start=p.cancel_entries[machine.state])
      self.assertEqual(saved[WINDOW], original_window, machine.steps)
      self.assertEqual(saved[LOWER], original_lower)
      self.assertEqual(positions, [len(prefix) if t == WINDOW else 0
                                   for t in range(p.ntapes)])
      for tape in p.scratch:
        self.assertTrue(all(v == BLANK for v in saved[tape].values()),
                        (machine.steps, tape))
      if machine.done:
        break
      if machine.state in p.ready:
        tapes[chain.PORT][0] = next(remaining)
      machine.step()

  def test_short_prefix_and_right_streams(self):
    p = chain.build_chain_program("ab")
    for n in range(1, 8):
      for bits in product("ab", repeat=n):
        prefix = "".join(bits)
        for right in ("", "a", "b", "aabababb", prefix[::-1]):
          got, used, _ = run(p, prefix, right)
          self.assertEqual((got, used), reference(prefix, right, 0), (prefix, right))

  def test_all_chain_outcomes_and_constant_work_per_place(self):
    p = chain.build_chain_program("ab")
    seen = set()
    for prefix in ("a" * 25, "baa" + "a" * 12, "ab" * 20,
                   "aabbbaaabbbaa" * 4, "aabababbbaa"):
      h = expected(prefix, 0)
      if h is None:
        continue
      ideal = "".join(prefix[-min(j % (2*h), 2*h - j % (2*h))-1]
                      for j in range(1, len(prefix)))
      rights = [ideal, ideal[:4*h], ideal[:4*h-1]]
      for j in range(min(len(ideal), 4*h+4)):
        rights.append(ideal[:j] + ("b" if ideal[j] == "a" else "a") + ideal[j+1:])
      for right in rights:
        got, used, costs = run(p, prefix, right)
        self.assertEqual((got, used), reference(prefix, right, 0), (prefix, right))
        self.assertLessEqual(max(costs, default=0), 12)
        seen.add(got)
    self.assertTrue({"palindrome", "failed_right_dp", "chain_shift",
                     "restart_search", "nonchain_move", "end_chain",
                     "end_pending"} <= seen, seen)


if __name__ == "__main__":
  unittest.main()
