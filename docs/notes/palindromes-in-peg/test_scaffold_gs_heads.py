import unittest

from gs_heads import HeadVM
from scaffold_gs_heads import fixture
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar, stack


class ScaffoldGSHeadsTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    with share_expressions():
      cls.circuit, cls.worker, cls.machine = fixture()

  def test_loaded_snapshot_and_every_executed_instruction(self):
    circuit, worker, machine = self.circuit, self.worker, self.machine
    for word in ("", "ab", "aabaaa"):
      vm = HeadVM(word, worker.program)
      node = machine.evaluate(word + "!" + "." * (len(word) + 1))
      self.assertEqual(scalar(circuit, node, worker.mode_key), "run")
      observed = []
      while not vm.done:
        vm.step()
        node = machine.step(node, ".")
        self.assertEqual(scalar(circuit, node, worker.pc_key), vm.state)
        self.assertFalse(scalar(circuit, node, "circuit.fault"))
        if scalar(circuit, node, "gs.border"):
          pair = worker.bank.counters["Origin", "KP"]
          observed.append(len(stack(circuit, node, pair.neg)) - len(stack(circuit, node, pair.pos)))
        for name in ("A", "B", "P"):
          tape = worker.tapes[name]
          location = vm.positions[name]
          wanted = word[location] if location < len(word) else "_"
          self.assertEqual(scalar(circuit, node, tape.symbol_key), wanted,
                           (word, vm.steps, name, location))
      node = machine.step(node, ".")
      self.assertEqual(observed, vm.outputs)
      self.assertEqual(scalar(circuit, node, worker.mode_key), "done")
      self.assertEqual(node.labels["accept"], bool(vm.outputs))

  def test_emitted_grammar_has_bounded_construction_size(self):
    source = self.machine.compile()
    self.assertLess(len(source), 3000000)
    # Plain productions, no embedded machine/interpreter or grammar macros.
    self.assertTrue(source.startswith("S = "))
    self.assertNotIn("<%", source)


if __name__ == "__main__":
  unittest.main()
