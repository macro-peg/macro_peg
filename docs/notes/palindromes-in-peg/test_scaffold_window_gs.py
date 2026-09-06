import itertools
import unittest

from gs_heads import Program, HeadVM
from scaffold_window_gs import fixture
from scaffold_optimize import optimize
from symbolic_sca2peg import share_expressions
from phase_peg import Grammar
from test_scaffold_circuit_program import scalar
from test_scaffold_window_counter import decode


def two_ended_program():
  # A small independent program using the same finite instruction set.
  # It recognizes a palindrome only after explicit work symbols; this
  # fixture is not a grammar for raw PAL.
  return Program((
    (("copy", "A", "Origin"), (1,)),
    (("copy", "B", "OriginalEnd"), (2,)),
    (("less", "A", "B"), (7, 3)),
    (("move", (("B", -1),)), (4,)),
    (("symbols", "A", "B"), (8, 5)),
    (("move", (("A", 1),)), (6,)),
    (("less", "A", "B"), (7, 3)),
    (("border", "B"), (8,)),
    (("halt",), ()),
  ), 0, 8)


class WindowGSTest(unittest.TestCase):
  def test_burst_pc_positions_and_plain_peg(self):
    program = two_ended_program()
    quantum = 5
    with share_expressions():
      circuit, worker, machine = fixture(quantum, program)
      projected, _ = optimize(machine)
    grammar = Grammar(projected.compile())
    words = ["".join(w) for n in range(5) for w in itertools.product("ab", repeat=n)]
    words += ["ababbaba", "a" * 17, "ab" * 9, "abba" * 5]
    for word in words:
      observer = HeadVM(word, program)
      node = machine.evaluate(word + "!")
      trace = word + "!"
      for _ in range(4 * len(word) + 10):
        if scalar(circuit, node, worker.mode_key) == "done": break
        node = machine.step(node, ".")
        trace += "."
        for _ in range(quantum):
          if not observer.done: observer.step()
        self.assertFalse(scalar(circuit, node, "circuit.fault"), (word, trace))
        self.assertEqual(scalar(circuit, node, worker.pc_key), observer.state, word)
        for head in ("A", "B", "OriginalEnd"):
          distance = worker.positions.counters["Origin", head]
          self.assertEqual(-decode(circuit, node, distance), observer.positions[head], (word, head))
      self.assertEqual(scalar(circuit, node, worker.mode_key), "done", word)
      expected = word == word[::-1]
      self.assertEqual(node.labels[machine.accepting], expected, word)
      self.assertEqual(grammar.accepts(trace[::-1]), expected, word)
    self.assertFalse(grammar.accepts("a.a!.."[::-1]))


if __name__ == "__main__":
  unittest.main()
