"""Finite circuit lowering exercises local cells, aliases, and tape history."""
from itertools import product
import unittest

from phase_peg import Grammar
from scaffold_circuit import Circuit, Value, PREVIOUS, TRUE, FALSE, conjunction, neg, choose
from scaffold_circuit_structs import Tape


def balanced_tape(quantum=1):
  circuit = Circuit()
  tape = Tape(circuit, "t", "_a", slots=quantum)
  input_value = circuit.input()
  before = circuit.get(PREVIOUS, "before", (False, True), True).eq(True)
  live = circuit.get(PREVIOUS, "live", (False, True), True).eq(True)
  a, b = input_value.eq("a"), input_value.eq("b")
  live = conjunction(live, neg(conjunction(a, neg(before))))
  for _ in range(quantum):
    tape.write(Value.constant("a"), a)
    tape.move(1, a)
    live = conjunction(live, neg(conjunction(b, tape.left.empty())))
    tape.move(-1, b)
  before = conjunction(before, neg(b))
  circuit.put("before", Value.select(before, Value.constant(True), Value.constant(False)))
  circuit.put("live", Value.select(live, Value.constant(True), Value.constant(False)))
  tape.finalize()
  return circuit.machine(conjunction(live, b, tape.left.empty()))


class ScaffoldCircuitTest(unittest.TestCase):
  def test_multiple_local_tape_pushes_and_pops_compile(self):
    for quantum in (1, 2):
      machine = balanced_tape(quantum)
      grammar = Grammar(machine.compile())
      for n in range(7):
        for chars in product("ab", repeat=n):
          word = "".join(chars)
          expected = n > 0 and n % 2 == 0 and word == "a" * (n // 2) + "b" * (n // 2)
          self.assertEqual(machine.run(word), expected, (quantum, word))
          self.assertEqual(grammar.accepts(word[::-1]), expected, (quantum, word))

  def test_finite_binary_value_mapping_and_selection(self):
    circuit = Circuit()
    count = circuit.get(PREVIOUS, "count", tuple(range(4)), 0)
    value = Value.select(circuit.input().eq("a"), count.map(lambda n: (n + 1) % 4), count)
    circuit.put("count", value)
    machine = circuit.machine(value.eq(3))
    grammar = Grammar(machine.compile())
    for n in range(7):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        expected = word.count("a") % 4 == 3
        self.assertEqual(machine.run(word), expected, word)
        self.assertEqual(grammar.accepts(word[::-1]), expected, word)


if __name__ == "__main__":
  unittest.main()
