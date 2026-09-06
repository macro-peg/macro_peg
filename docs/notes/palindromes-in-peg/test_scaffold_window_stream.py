import random
import unittest

from phase_peg import Grammar
from scaffold_window_stream import fixture
from scaffold_optimize import optimize
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar


def apply(positions, char, size, quantum):
  if char == "a": positions["x"] = min(size, positions["x"] + quantum)
  elif char == "b": positions["x"] = max(0, positions["x"] - quantum)
  elif char == "c": positions["y"] = positions["x"]
  elif char == "d": positions["x"] = positions["y"]
  elif char == "e": positions["y"] = min(size, positions["y"] + 1)
  elif char == "f": positions["y"] = max(0, positions["y"] - 1)


class WindowStreamTest(unittest.TestCase):
  def test_positions_block_crossings_live_blocks_and_copied_heads(self):
    for quantum in (1, 3, 9):
      with share_expressions():
        circuit, stream, observations, machine = fixture(quantum)
      root, data, positions = machine.initial_node(), "", {"x": 0, "y": 0}
      identities = {id(root): 0}
      rng = random.Random(913)
      # Leave the live block, let it complete, then return through its queue.
      word = "a" * 35 + "b" * 2 + "h" * 70 + "a" * 40 + "b" * 45 + "cd" + \
             "".join(rng.choice("abcdefgh") for _ in range(170))
      for index, char in enumerate(word, 1):
        data += char
        apply(positions, char, index, quantum)
        root = machine.step(root, char)
        identities[id(root)] = index
        self.assertFalse(scalar(circuit, root, "circuit.fault"), (quantum, index, char, positions))
        for name in stream.names:
          state = stream.states[name]
          low = sum(1 << bit for bit in range(stream.width)
                    if scalar(circuit, root, f"{name}.offset.{bit}"))
          live = scalar(circuit, root, state.live_key)
          actual = (index // stream.base) * stream.base + low if live else \
                   identities[id(root.pointers[state.focus_key])] - stream.base + low
          self.assertEqual(actual, positions[name], (quantum, index, char, name))
          expected = data[actual] if actual < index else None
          self.assertEqual(scalar(circuit, root, observations[name]), expected,
                           (quantum, index, char, name, actual))

  def test_ordinary_peg_uses_unexpanded_input(self):
    with share_expressions():
      _, _, _, machine = fixture(1)
      machine, _ = optimize(machine)
    grammar = Grammar(machine.compile())
    words = ["", "a", "b", "ha", "ab", "haabc", "aaaaabhhha", "ahhbhhha",
             "h" * 17 + "a" * 10 + "b" * 6 + "cdeaf"]
    for word in words:
      positions = {"x": 0, "y": 0}
      for index, char in enumerate(word, 1):
        apply(positions, char, index, 1)
      expected = positions["x"] < len(word) and word[positions["x"]] == "a"
      self.assertEqual(grammar.accepts(word[::-1]), expected, (word, positions))

  def test_persisted_heap_does_not_grow_with_number_of_internal_moves(self):
    with share_expressions():
      _, _, _, small = fixture(1)
      _, _, _, large = fixture(15)
    self.assertLessEqual(len(large.pointers) - len(small.pointers), 20)
    self.assertLessEqual(len(large.labels) - len(small.labels), 30)


if __name__ == "__main__":
  unittest.main()
