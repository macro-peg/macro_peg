import random
import unittest

from phase_peg import Grammar
from scaffold_circuit import Circuit, Value, conjunction
from scaffold_head_distances import HeadDistances
from symbolic_sca2peg import share_expressions


ACTIONS = {
  "a": ("move", "A", 1), "b": ("move", "B", 1), "c": ("move", "C", 1),
  "d": ("copy", "A", "B"), "e": ("copy", "B", "C"), "f": ("copy", "C", "A"),
  "g": ("move", "A", -1), "h": ("move", "B", -1), "i": ("move", "C", -1),
}


def fixture(exclusive_moves=False):
  circuit = Circuit("".join(ACTIONS))
  bank = HeadDistances(circuit, ("A", "B", "C"), exclusive_moves=exclusive_moves)
  for char, (operation, head, other) in ACTIONS.items():
    enabled = circuit.input().eq(char)
    (bank.move if operation == "move" else bank.copy)(head, other, enabled)
  observations = {}
  for left in bank.names:
    for right in bank.names:
      for relation, test in (("eq", bank.equal), ("lt", bank.less)):
        key = f"{relation}.{left}.{right}"
        circuit.put(key, Value.select(test(left, right), Value.constant(True), Value.constant(False)),
                    (False, True), relation == "eq")
        observations[relation, left, right] = circuit._label(key, 0)
  bank.finalize()
  return circuit.machine(conjunction(bank.equal("A", "B"), bank.less("B", "C"))), observations


def apply(positions, char):
  op, head, other = ACTIONS[char]
  positions[head] = positions[head] + other if op == "move" else positions[other]


class HeadDistancesTest(unittest.TestCase):
  def test_all_pair_orders_after_moves_and_copies(self):
    self.compare_commands(False)
    self.compare_commands(True)

  def compare_commands(self, exclusive_moves):
    with share_expressions():
      machine, observations = fixture(exclusive_moves)
    root, positions = machine.initial_node(), dict.fromkeys("ABC", 0)
    rng = random.Random(907)
    word = "a" * 24 + "g" * 48 + "f" + "b" * 17 + "def" + \
           "".join(rng.choice(tuple(ACTIONS)) for _ in range(200))
    for char in word:
      apply(positions, char)
      root = machine.step(root, char)
      for (relation, left, right), label in observations.items():
        expected = positions[left] == positions[right] if relation == "eq" else positions[left] < positions[right]
        self.assertEqual(root.labels[label], expected, (char, positions, relation, left, right))

  def test_order_test_survives_ordinary_peg_translation(self):
    with share_expressions():
      machine, _ = fixture()
    grammar = Grammar(machine.compile())
    rng = random.Random(908)
    samples = ["", "c", "ac", "abc", "aadc", "cfa", "ahdfccc", "gigidec"]
    samples.extend("".join(rng.choice(tuple(ACTIONS)) for _ in range(rng.randrange(1, 16)))
                   for _ in range(35))
    for word in samples:
      positions = dict.fromkeys("ABC", 0)
      for char in word:
        apply(positions, char)
      expected = positions["A"] == positions["B"] < positions["C"]
      self.assertEqual(grammar.accepts(word[::-1]), expected, (word, positions))

  def test_invalid_bank_and_move(self):
    with self.assertRaises(ValueError):
      HeadDistances(Circuit(), ("A", "A"))
    bank = HeadDistances(Circuit(), ("A", "B"))
    with self.assertRaises(ValueError):
      bank.move("A", 2)
    with self.assertRaises(ValueError):
      bank.copy("A", "Z")


if __name__ == "__main__":
  unittest.main()
