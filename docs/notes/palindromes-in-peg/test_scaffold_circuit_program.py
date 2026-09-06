"""Compare emitted scaffold equations to actual finite FPP instructions."""
import unittest

from fpp_finite import LEFT, END, BLANK
from fpp_subroutine import build_marked_program, SOURCE
from scaffold_circuit import Circuit, Value, conjunction, disjunction
from scaffold_circuit_program import Program
from phase_peg import Grammar


def fixture(quantum=1, coarse=False, shared_cells=False):
  kernel = build_marked_program("ab")
  circuit = Circuit("^ab$<!.")
  lowered = Program(circuit, kernel, "f", quantum=quantum + 1, coarse=coarse,
                    shared_cells=shared_cells, extra_moves=2)
  char = circuit.input()
  loading = disjunction(*(char.eq(s) for s in LEFT + "ab" + END))
  lowered.tapes[SOURCE].write(Value({s: char.eq(s) for s in LEFT + "ab" + END}), loading)
  lowered.tapes[SOURCE].move(1, loading)
  lowered.tapes[SOURCE].move(-1, char.eq("<"))
  lowered.start(char.eq("!"))
  for _ in range(quantum):
    lowered.step(char.eq("."))
  lowered.finalize()
  return circuit, lowered, circuit.machine(conjunction(char.eq("."), lowered.done))


def scalar(circuit, node, key):
  domain = circuit.domains[key][0]
  bits = range(max(0, (len(domain) - 1).bit_length()))
  index = sum(1 << bit for bit in bits if node.labels[circuit._label(key, bit)])
  return domain[index]


def stack(circuit, node, view):
  current = node.pointers[view.root_key]
  tag = scalar(circuit, node, view.tag_key)
  answer = []
  while current is not None:
    answer.append(scalar(circuit, current, view.pool.key(tag, "data")))
    below = current.pointers[view.pool.key(tag, "below")]
    tag = scalar(circuit, current, view.pool.key(tag, "tag"))
    current = below
  return answer


class CircuitProgramTest(unittest.TestCase):
  def test_real_fpp_table_matches_each_instruction_and_final_tape(self):
    self.compare(1)
    self.compare(3)

  def test_read_blocks_match_raw_instructions_with_shared_local_slots(self):
    self.compare(1, coarse=True)
    self.compare(3, coarse=True)

  def test_mutually_exclusive_tape_moves_share_instruction_cells(self):
    self.compare(1, shared_cells=True)
    self.compare(3, shared_cells=True)

  def compare(self, quantum, coarse=False, shared_cells=False):
    from read_blocks import step as block_step
    circuit, lowered, machine = fixture(quantum, coarse, shared_cells)
    # The complete table really compiles to ordinary rule text.
    source = machine.compile()
    self.assertLess(len(source), 6000000)
    self.assertTrue(Grammar(source).accepts("."))
    for word in ("", "a", "abba", "aababa"):
      root = machine.evaluate(LEFT + word + END + "<" * (len(word) + 2) + "!")
      tapes = [{} for _ in lowered.tapes]
      tapes[SOURCE] = dict(enumerate(LEFT + word + END))
      expected = lowered.kernel.execution(tapes, [0] * len(tapes))
      while not expected.done:
        for _ in range(quantum):
          if not expected.done:
            if coarse: block_step(expected)
            else: expected.step()
        root = machine.step(root, ".")
        self.assertEqual(scalar(circuit, root, lowered.pc_key), expected.state)
        self.assertEqual(scalar(circuit, root, lowered.done_key), expected.done)
        for index, tape in enumerate(lowered.tapes):
          self.assertEqual(scalar(circuit, root, tape.symbol_key),
                           expected.tapes[index].get(expected.positions[index], BLANK))
      for index, tape in enumerate(lowered.tapes):
        left, right = stack(circuit, root, tape.left), stack(circuit, root, tape.right)
        self.assertEqual(len(left), expected.positions[index])
        contents = list(reversed(left)) + [scalar(circuit, root, tape.symbol_key)] + right
        actual = {i: value for i, value in enumerate(contents) if value != BLANK}
        wanted = {i: value for i, value in expected.tapes[index].items() if value != BLANK}
        self.assertEqual(actual, wanted, (word, index))


if __name__ == "__main__":
  unittest.main()
