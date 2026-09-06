import itertools
import unittest

from gs_heads import HeadVM
from scaffold_window_gs_live import fixture
from scaffold_optimize import optimize
from symbolic_sca2peg import share_expressions
from phase_peg import Grammar
from test_scaffold_circuit_program import scalar
from test_scaffold_window_counter import decode
from test_scaffold_window_gs import two_ended_program


class WindowGSLiveTest(unittest.TestCase):
  def check_machine(self, program, quantum, words, peg=True):
    with share_expressions():
      circuit, worker, machine = fixture(quantum, program)
      projected, _ = optimize(machine)
    grammar = Grammar(projected.compile()) if peg else None
    for word in words:
      observer = HeadVM(word, worker.program)
      node, trace = machine.evaluate(word + "!"), word + "!"
      for _ in range(1000 * (len(word) + 1)):
        if scalar(circuit, node, worker.mode_key) == "done": break
        node, trace = machine.step(node, "."), trace + "."
        for _ in range(quantum):
          if not observer.done: observer.step()
        self.assertFalse(scalar(circuit, node, "circuit.fault"), word)
        self.assertEqual(scalar(circuit, node, worker.pc_key), observer.state, word)
        distances = worker.distances
        for pair in distances.analysis.before[observer.state]:
          key = distances.keys[distances.analysis.colors[pair]]
          self.assertEqual(decode(circuit, node, distances.values.bank.counters[key]),
                           observer.positions[pair[0]] - observer.positions[pair[1]], (word, pair))
      self.assertEqual(scalar(circuit, node, worker.mode_key), "done", word)
      self.assertEqual(node.labels[machine.accepting], bool(observer.outputs), word)
      if grammar is not None:
        self.assertEqual(grammar.accepts(trace[::-1]), bool(observer.outputs), word)

  def test_plain_peg_small_independent_program(self):
    words = ["".join(w) for n in range(4) for w in itertools.product("ab", repeat=n)]
    words += ["abbaabba", "abbababb", "a" * 17]
    self.check_machine(two_ended_program(), 5, words)

  def test_full_batch_controller_live_registers(self):
    self.check_machine(None, 3, ("", "a", "ab", "aa", "aba", "abb", "abab", "abba"), False)


if __name__ == "__main__":
  unittest.main()
