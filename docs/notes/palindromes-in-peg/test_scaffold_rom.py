import itertools
import unittest

from gs_heads import HEADS, compile_controller, unit_moves
from gs_head_liveness import analyze_readers
from scaffold_circuit import Circuit, Value, PREVIOUS
from scaffold_rom import ROM, controller_table
from scaffold_window_counter import Bits
from symbolic_sca2peg import TRUE, FALSE, share_expressions
from phase_peg import Grammar


class ROMTest(unittest.TestCase):
  def test_every_gs_instruction_decodes_exactly(self):
    program = unit_moves(compile_controller())
    readers = analyze_readers(program)
    names = tuple(f"r{i}" for i in range(readers.registers))
    table = controller_table(program, HEADS, readers, names)
    for state, wanted in enumerate(table.rows):
      actual = table.read(Bits.constant(state, table.width).bits)
      for key, value in actual.items():
        self.assertTrue(all(bit in (TRUE, FALSE) for bit in value.bits))
        index = sum(1 << bit for bit, test in enumerate(value.bits) if test == TRUE)
        self.assertEqual(value.domain[index], wanted[key], (state, key))

  def test_sequential_table_steps_compile_to_ordinary_peg(self):
    rows = [dict(a=(2 * state + 1) % 7, b=(3 * state + 2) % 7, accept=state in (0, 3))
            for state in range(7)]
    table = ROM(rows, dict(a=range(7), b=range(7), accept=(False, True)))
    with share_expressions():
      circuit = Circuit()
      pc = circuit.get(PREVIOUS, "pc", tuple(range(7)), 0)
      for _ in range(3):
        fields = table.read(pc.bits)
        pc = Value.select(circuit.input().eq("a"), fields["a"], fields["b"])
      answer = table.read(pc.bits)["accept"].eq(True)
      circuit.put("pc", pc)
      machine = circuit.machine(answer, initial_accepting=True)
    grammar = Grammar(machine.compile())
    for length in range(7):
      for chars in itertools.product("ab", repeat=length):
        word, pc = "".join(chars), 0
        for char in word:
          for _ in range(3): pc = rows[pc][char]
        self.assertEqual(grammar.accepts(word[::-1]), pc in (0, 3), word)


if __name__ == "__main__":
  unittest.main()
