"""Symbolic input heads must retain places across movement and cloning."""
import random
import unittest

from scaffold_circuit import Circuit, Value, NEW, TRUE, conjunction, disjunction, neg
from scaffold_circuit_structs import StackPool
from scaffold_circuit_input import PlaceHead
from test_scaffold_circuit_program import scalar, stack


def fixture():
  c = Circuit("abrlRLc.")
  char = c.input()
  arrival = disjunction(char.eq("a"), char.eq("b"))
  c.put("input", Value.select(arrival, Value({s: char.eq(s) for s in "ab"}), Value.constant("a")),
        tuple("ab"), "a")
  heads = StackPool(c, {f"{name}.{side}": 1 for name in ("X", "Y") for side in ("l", "r")})
  queues = StackPool(c, {f"{name}.in.{role}": count for name in ("X", "Y")
                        for role, count in {"F": 0, "B": 1, "B2": 1, "Fr": 12,
                                            "Br": 24, "WF": 0, "WB": 0}.items()})
  counters = StackPool(c, {f"{name}.in.{role}": count for name in ("X", "Y")
                          for role, count in {"m.pos": 12, "m.neg": 13,
                                              "c.pos": 24, "c.neg": 2}.items()})
  x, y = (PlaceHead(heads, queues, counters, name) for name in ("X", "Y"))
  for head in (x, y): head.append(NEW, arrival)
  for head, right, left in ((x, "r", "l"), (y, "R", "L")):
    head.right(conjunction(char.eq(right), head.can_right()))
    head.left(conjunction(char.eq(left), neg(head.read().eq(None))))
  y.copy_from(x, char.eq("c"))
  x.finalize(); y.finalize()
  return c, (x, y), c.machine(TRUE, initial_accepting=True)


class CircuitInputTest(unittest.TestCase):
  def test_two_clonable_place_heads_match_integer_observer(self):
    circuit, heads, machine = fixture()
    root = machine.initial_node()
    random.seed(94)
    commands = "abrrrRcLrRl" + "".join(random.choices("abrlRLc.", k=100))
    word, positions = "", [0, 0]
    for command in commands:
      if command in "ab": word += command
      for i, (right, left) in enumerate((("r", "l"), ("R", "L"))):
        if command == right: positions[i] = min(positions[i] + 1, 2 * len(word))
        if command == left: positions[i] = max(positions[i] - 1, 0)
      if command == "c": positions[1] = positions[0]
      root = machine.step(root, command)
      self.assertTrue(root.labels[machine.accepting], command)
      for i, head in enumerate(heads):
        focus = root.pointers[head.head.focus_key]
        if focus is None:
          place = 0
        else:
          depth = len(stack(circuit, root, head.head.left_stack))
          place = 2 * depth - 1 + int(scalar(circuit, root, head.gap_key))
          self.assertEqual(scalar(circuit, focus, "input"), word[depth - 1])
        self.assertEqual(place, positions[i], command)


if __name__ == "__main__":
  unittest.main()
