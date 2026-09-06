import random
import unittest

from phase_peg import Grammar
from scaffold_circuit import Circuit
from scaffold_window_counter import fixture, WindowCounters
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar, stack


def apply(values, char):
  x, y = values
  if char == "a": x += 7
  elif char == "b": x -= 5
  elif char == "c": y = x
  elif char == "d": x = y
  elif char == "e": y -= 3
  elif char == "f": x = 0
  elif char == "g": x = -x
  elif char == "h": y = -x
  return x, y


def decode(circuit, root, counter):
  quotient = len(stack(circuit, root, counter.quotient.pos)) - \
             len(stack(circuit, root, counter.quotient.neg))
  digits = sum(1 << bit for bit in range(counter.bank.width)
               if scalar(circuit, root, f"{counter.name}.low.{bit}"))
  if digits >= counter.bank.base // 2:
    digits -= counter.bank.base
  return quotient * counter.bank.base + digits


class WindowCounterTest(unittest.TestCase):
  def test_crossings_copies_negations_and_queries_before_normalization(self):
    with share_expressions():
      circuit, bank, machine = fixture()
    root, values = machine.initial_node(), (0, 0)
    rng = random.Random(910)
    commands = "a" * 70 + "gch" + "b" * 200 + "g" + "e" * 80 + "dghf" + \
               "".join(rng.choice("abcdefgh") for _ in range(1000))
    for index, char in enumerate(commands):
      values = apply(values, char)
      root = machine.step(root, char)
      for name, value in zip(bank.names, values):
        counter = bank.counters[name]
        self.assertEqual(decode(circuit, root, counter), value, (index, char, values))
        for relation, expected in (("zero", value == 0), ("negative", value < 0), ("positive", value > 0)):
          self.assertEqual(scalar(circuit, root, name + "." + relation), expected,
                           (index, char, name, relation, value))

  def test_ordinary_peg_on_original_commands(self):
    with share_expressions():
      _, _, machine = fixture()
    source = machine.compile()
    self.assertLess(len(source), 30000)
    grammar = Grammar(source)
    rng = random.Random(911)
    cases = ["", "a", "f", "aaaaabbbbbbb", "a" * 32 + "gchd", "b" * 33 + "gf", "abcghd"]
    cases.extend("".join(rng.choice("abcdefgh") for _ in range(rng.randrange(1, 45)))
                 for _ in range(70))
    for word in cases:
      values = (0, 0)
      for char in word:
        values = apply(values, char)
      self.assertEqual(grammar.accepts(word[::-1]), values[0] == 0, (word, values))

  def test_large_batch_needs_one_cell_slot_per_register(self):
    circuit = Circuit()
    bank = WindowCounters(circuit, ("x",), 65535)
    counter = bank.counters["x"]
    counter.add(32767, circuit.input().eq("a"))
    counter.add(-32768, circuit.input().eq("b"))
    answer = counter.zero()
    bank.finalize()
    machine = circuit.machine(answer, initial_accepting=True)
    root = machine.initial_node()
    value = 0
    for char in "aaabbbbaabba":
      value += 32767 if char == "a" else -32768
      root = machine.step(root, char)
      self.assertEqual(decode(circuit, root, counter), value)
    self.assertEqual(len(bank.pool.tags), 1)
    self.assertLess(len(machine.compile()), 20000)

  def test_window_is_checked_at_construction(self):
    bank = WindowCounters(Circuit(), ("x", "y"), 7)
    x, y = bank.counters.values()
    x.add(7)
    with self.assertRaises(ValueError): x.inc()
    y.copy_from(x)
    with self.assertRaises(ValueError): y.dec()
    x.reset()
    x.inc()
    bank.finalize()
    with self.assertRaises(ValueError): x.inc()


if __name__ == "__main__":
  unittest.main()
