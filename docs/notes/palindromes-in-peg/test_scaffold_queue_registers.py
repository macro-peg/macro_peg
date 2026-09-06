from collections import deque
import random
import unittest

from scaffold_queue_registers import fixture
from symbolic_sca2peg import share_expressions
from phase_peg import Grammar
from test_scaffold_circuit_program import scalar


class QueueRegistersTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    with share_expressions():
      cls.circuit, cls.bank, cls.machine = fixture()

  def test_independent_queues_and_copies_during_rotations(self):
    root = self.machine.initial_node()
    queues = [deque(), deque()]
    rng = random.Random(914)
    commands = "".join(c + "..." for c in "a" * 40 + "b" * 30)
    commands += "x" + "".join(">..." if i % 2 else "],,," for i in range(95))
    # A copy may preserve an in-flight rotation. Service both snapshots before
    # their next public operation; advancing one must not mutate the other.
    commands += "a.x..,,," + "b.y..,,,"
    for _ in range(180):
      command = rng.choice("abAB>]xy")
      commands += command
      if command in "ab>":
        commands += "..."
      elif command in "AB]":
        commands += ",,,"
    for command in commands:
      expected = False
      if command in "abAB":
        queues[int(command.isupper())].append(command.lower())
      elif command in ">]":
        queue = queues[int(command == "]")]
        if queue:
          expected = queue.popleft() == "a"
      elif command == "x":
        queues[1] = deque(queues[0])
      elif command == "y":
        queues[0] = deque(queues[1])
      root = self.machine.step(root, command)
      self.assertFalse(scalar(self.circuit, root, "circuit.fault"), command)
      self.assertEqual(root.labels["accept"], expected, command)

  def test_shared_queue_kernel_executes_as_plain_peg(self):
    source = self.machine.compile()
    self.assertLess(len(source), 250000)
    grammar = Grammar(source)
    for trace, expected in (("a...>", True), ("b...>", False),
                            ("a...b...>...>", False), ("a.x..,,,]", True),
                            ("A,,,y>", True)):
      self.assertEqual(grammar.accepts(trace[::-1]), expected, trace)


if __name__ == "__main__":
  unittest.main()
