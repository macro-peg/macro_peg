import random
import unittest

from phase_peg import Grammar
from scaffold_circuit import Circuit, Value
from scaffold_window_positions import WindowPositions
from symbolic_sca2peg import share_expressions
from test_scaffold_head_distances import ACTIONS, apply
from test_scaffold_circuit_program import scalar
from test_scaffold_window_counter import decode


def fixture(quantum=5):
  circuit = Circuit("".join(ACTIONS))
  positions = WindowPositions(circuit, ("A", "B", "C"), 6 * quantum)
  for _ in range(quantum):
    for char, (op, head, other) in ACTIONS.items():
      guard = circuit.input().eq(char)
      (positions.move if op == "move" else positions.copy)(head, other, guard)
  observations = {}
  for left in positions.names:
    for right in positions.names:
      equal, less = positions.compare(left, right)
      for relation, condition in (("eq", equal), ("lt", less)):
        key = f"{relation}.{left}.{right}"
        circuit.put(key, Value.select(condition, Value.constant(True), Value.constant(False)),
                    (False, True), relation == "eq")
        observations[relation, left, right] = key
  answer = positions.less("A", "B")
  positions.finalize()
  return circuit, positions, observations, circuit.machine(answer)


class WindowPositionsTest(unittest.TestCase):
  def test_orders_and_final_distances_after_conditional_copies(self):
    with share_expressions():
      circuit, bank, observations, machine = fixture()
    root, values = machine.initial_node(), dict.fromkeys(bank.names, 0)
    rng = random.Random(917)
    word = "a" * 100 + "b" * 150 + "fed" + "g" * 180 + "h" * 100 + \
           "".join(rng.choice(tuple(ACTIONS)) for _ in range(300))
    for index, char in enumerate(word):
      for _ in range(5):
        apply(values, char)
      root = machine.step(root, char)
      for (relation, left, right), key in observations.items():
        expected = values[left] == values[right] if relation == "eq" else values[left] < values[right]
        self.assertEqual(scalar(circuit, root, key), expected, (index, char, key, values))
      for (left, right), counter in bank.counters.items():
        self.assertEqual(decode(circuit, root, counter), values[left] - values[right],
                         (index, char, left, right, values))

  def test_ordinary_peg_compares_the_current_heads(self):
    with share_expressions():
      _, _, _, machine = fixture(2)
    grammar = Grammar(machine.compile())
    rng = random.Random(918)
    for _ in range(50):
      word = "".join(rng.choice(tuple(ACTIONS)) for _ in range(rng.randrange(40)))
      values = dict.fromkeys("ABC", 0)
      for char in word:
        for _ in range(2): apply(values, char)
      self.assertEqual(grammar.accepts(word[::-1]), values["A"] < values["B"], (word, values))


if __name__ == "__main__":
  unittest.main()
