import random
import unittest

from scaffold_circuit import Circuit, Value, TRUE, FALSE
from scaffold_window_registers import WindowRegisters
from symbolic_sca2peg import share_expressions
from scaffold_optimize import optimize
from phase_peg import Grammar
from test_scaffold_circuit_program import scalar
from test_scaffold_window_counter import decode


class WindowRegistersTest(unittest.TestCase):
  def test_signed_origins_and_simultaneous_transfers(self):
    with share_expressions():
      c = Circuit("abcdefg")
      bank = WindowRegisters(c, ("x", "y", "z"), 24)
      char = c.input()
      for _ in range(3):
        saved = dict(bank.registers)
        for target, source in (("x", "y"), ("y", "z"), ("z", "x")):
          bank.assign(target, saved[source], char.eq("c"))
        bank.add("x", 5, char.eq("a"))
        bank.add("y", -3, char.eq("b"))
        bank.assign("y", bank.registers["x"], char.eq("d"), TRUE)
        bank.reset("z", char.eq("e"))
        bank.assign("x", bank.registers["x"], char.eq("f"), TRUE)
        bank.add("z", 2, char.eq("g"))
      for name, register in bank.registers.items():
        for label, bit in zip(("zero", "less"), bank.compare_zero(register)):
          c.put(name + "." + label, Value.select(bit, Value.constant(True), Value.constant(False)),
                (False, True), label == "zero")
      accepting = bank.compare_zero(bank.registers["x"])[1]
      bank.finalize()
      machine = c.machine(accepting)
      projected, _ = optimize(machine)
    grammar = Grammar(projected.compile())
    rng = random.Random(702)
    commands = "a" * 20 + "b" * 20 + "cfdca" + "".join(rng.choice("abcdefg") for _ in range(250))
    node, expected, trace = machine.initial_node(), dict.fromkeys("xyz", 0), ""
    for char in commands:
      node, trace = machine.step(node, char), trace + char
      for _ in range(3):
        if char == "a": expected["x"] += 5
        elif char == "b": expected["y"] -= 3
        elif char == "c": expected = dict(zip("xyz", (expected["y"], expected["z"], expected["x"])))
        elif char == "d": expected["y"] = -expected["x"]
        elif char == "e": expected["z"] = 0
        elif char == "f": expected["x"] = -expected["x"]
        elif char == "g": expected["z"] += 2
      for name in "xyz":
        self.assertEqual(decode(c, node, bank.bank.counters[name]), expected[name], (trace, name))
        self.assertEqual(scalar(c, node, name + ".zero"), expected[name] == 0)
        self.assertEqual(scalar(c, node, name + ".less"), expected[name] < 0)
      if len(trace) < 60:
        self.assertEqual(grammar.accepts(trace[::-1]), expected["x"] < 0, trace)


if __name__ == "__main__":
  unittest.main()
