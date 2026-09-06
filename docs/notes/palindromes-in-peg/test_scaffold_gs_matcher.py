import unittest

from gs_match_heads import StreamingMatcher
from scaffold_gs_matcher import fixture
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar


class ScaffoldGSMatcherTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    with share_expressions():
      cls.circuit, cls.worker, cls.machine = fixture()

  def test_real_queues_and_every_matching_instruction(self):
    c, worker, machine = self.circuit, self.worker, self.machine
    for prefix, text in (("a", "baaa"), ("abb", "bbabba")):
      node = machine.initial_node()

      def step(char):
        nonlocal node
        node = machine.step(node, char)
        self.assertFalse(scalar(c, node, "circuit.fault"))

      for char in prefix:
        step(char)
        while scalar(c, node, worker.phase_key) != "idle":
          step(".")
      step("!")
      observer = StreamingMatcher(prefix[::-1], worker.program)

      def drain():
        while scalar(c, node, worker.phase_key) != "run" or not observer.waiting:
          phase = scalar(c, node, worker.phase_key)
          step(".")
          if phase == "run":
            observer.step()
          self.assertEqual(scalar(c, node, worker.pc_key), observer.state)

      drain()
      for end, char in enumerate(text, 1):
        observer.append(char)
        step(char)
        drain()
        expected = end >= len(prefix) and text[end - len(prefix):end] == prefix[::-1]
        self.assertEqual(node.labels["accept"], expected, (prefix, text, end))


if __name__ == "__main__":
  unittest.main()
