"""Fixed ordinary PEG rules that consume floor(n/2) or ceil(n/2) symbols.

The source enqueues each input-node address and dequeues one on every second
arrival. Its focus therefore names node floor(n/2), including the sentinel.
Scaffold-to-PEG reversal makes that pointer consume n-floor(n/2) characters.
This constructs a midpoint component, not a palindrome recognizer.
"""
import argparse
import json
from pathlib import Path

from scaffold_circuit import Circuit, Value, NEW, PREVIOUS, Ref, neg
from scaffold_circuit_structs import StackPool, Queue
from scaffold_optimize import optimize
from symbolic_sca2peg import Scaffold, TRUE, present, pointer, share_expressions


def build():
  c = Circuit("ab")
  cells = StackPool(c, {"q.F": 0, "q.B": 1, "q.B2": 1, "q.Fr": 3,
                        "q.Br": 6, "q.WF": 0, "q.WB": 0})
  counters = StackPool(c, {"q.m.pos": 3, "q.m.neg": 4,
                           "q.c.pos": 6, "q.c.neg": 2})
  queue = Queue(cells, counters, "q")
  first = c.get(PREVIOUS, "first", (False, True), True).eq(True)
  odd = c.get(PREVIOUS, "odd", (False, True), False).eq(True)
  focus = Ref.select(first, PREVIOUS, c.get_ref(PREVIOUS, "half"))
  queue.push(NEW)
  # Three rotation units run before the optional pop. At rotation start,
  # rear <= front+2; reverse/switch/copy require <=2*front+3 units. Before
  # a (front+1)-st pop could need the new front, 3*(front+1) units are served.
  # See MIDPOINT.md for the queue and pointer invariants.
  queue.work()
  offered = queue.pop(odd)
  focus = Ref.select(odd, offered, focus)
  queue.finalize()
  c.put("first", Value.constant(False))
  c.put("odd", Value.select(neg(odd), Value.constant(True), Value.constant(False)))
  c.put_ref("half", focus)
  # The offered front is node ceil(n/2), whether or not this arrival pops it.
  c.put_ref("upper_half", offered)
  return c, c.machine(TRUE, initial_accepting=True)


def grammar():
  with share_expressions():
    _, machine = build()
    # Keep precisely the equations needed by the returned pointer. The full
    # machine above retains its fault monitor for independent state checks.
    root = "midpoint.present"
    observed = Scaffold({**machine.initial, root: False},
                         {**machine.labels, root: present(pointer(("half",)))},
                         machine.pointers, root, machine.alphabet)
    other = "midpoint.upper.present"
    observed = Scaffold({**observed.initial, other: False},
                         {**observed.labels, other: present(pointer(("upper_half",)))},
                         observed.pointers, root, observed.alphabet)
    projected, _ = optimize(observed, roots=(other,))
  half = f"P_{tuple(projected.pointers).index('half')}"
  upper = f"P_{tuple(projected.pointers).index('upper_half')}"
  rules = list(projected.iter_rules())
  # H's contract is for a binary suffix. S is only a standalone full-input
  # wrapper; it accepts all binary strings, not PAL.
  rules[0] = 'S = H ("a" / "b")* !.;'
  rules.extend((f'HalfCeil = !. / {half};', f'HalfFloor = !. / {upper};', 'H = HalfCeil;'))
  return "\n".join(rules) + "\n"


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("output", type=Path)
  args = parser.parse_args()
  source = grammar()
  args.output.write_text(source)
  print(json.dumps(dict(output=str(args.output), rules=source.count(";"),
                        bytes=len(source.encode()), palindrome_recognizer=False)))


if __name__ == "__main__":
  main()
