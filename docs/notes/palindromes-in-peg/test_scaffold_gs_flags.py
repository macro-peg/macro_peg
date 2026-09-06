import unittest

from gs_flag_heads import FlagVM
from gs_dual_flags import DualFlagVM
from scaffold_gs_flags import fixture
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar, stack


class ScaffoldGSFlagsTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    with share_expressions():
      cls.circuit, cls.worker, cls.machine = fixture()

  def test_frozen_mirror_view_and_entire_flag_stack(self):
    self.compare(self.circuit, self.worker, self.machine, FlagVM)

  def test_oppositely_oriented_views_and_entire_flag_stack(self):
    with share_expressions():
      circuit, worker, machine = fixture(dual=True)
    self.compare(circuit, worker, machine, DualFlagVM)

  def compare(self, c, worker, machine, observer_type):
    for word, lower in (("abba", 2), ("ababa", 2)):
      node = machine.initial_node()

      def step(char):
        nonlocal node
        node = machine.step(node, char)
        self.assertFalse(scalar(c, node, "circuit.fault"))

      for index, char in enumerate(word):
        if index == lower:
          step("|")
        step(char)
        while scalar(c, node, worker.phase_key) != "idle":
          step(".")
      step("!")
      observer = observer_type(word, lower, len(word), worker.program)
      while scalar(c, node, worker.phase_key) != "done":
        phase = scalar(c, node, worker.phase_key)
        step(".")
        if phase == "run" and not observer.done:
          observer.step()
        self.assertEqual(scalar(c, node, worker.pc_key), observer.state)
      expected = [word[:n] == word[:n][::-1] for n in range(lower, len(word))]
      self.assertEqual(stack(c, node, worker.flags), expected)


if __name__ == "__main__":
  unittest.main()
