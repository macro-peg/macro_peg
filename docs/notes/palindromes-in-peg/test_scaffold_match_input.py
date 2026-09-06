import random
import unittest

from scaffold_match_input import fixture
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar


class MatchInputTest(unittest.TestCase):
  def test_frozen_reverse_prefix_frontier_and_copied_heads(self):
    with share_expressions():
      circuit, _, machine = fixture()
    root = machine.initial_node()
    rng = random.Random(913)
    global_word, pattern, text = "", "", ""
    positions, active = [0, 0], False
    commands = list("abba!a>>b>>a[]x>y[")
    for tick in range(150):
      command = commands[tick] if tick < len(commands) else rng.choice("ab!<>[]xy.")
      if command in "ab":
        global_word += command
        if active:
          text += command
      elif command == "!":
        pattern, text = global_word[::-1], ""
        positions, active = [0, len(pattern)], True
      elif command in "<>[]":
        index = int(command in "[]")
        if command in "<[" and positions[index] == 0:
          command = "."
        elif command in ">]":
          positions[index] = min(positions[index] + 1, len(pattern) + len(text))
        else:
          positions[index] -= 1
      elif command == "x":
        positions[0] = positions[1]
      elif command == "y":
        positions[1] = positions[0]
      root = machine.step(root, command)
      self.assertFalse(scalar(circuit, root, "circuit.fault"), (tick, command))
      word = pattern + text
      for name, position in zip(("A", "B"), positions):
        expected = word[position] if active and position < len(word) else None
        self.assertEqual(scalar(circuit, root, name + ".observed"), expected,
                         (tick, command, name, position, word))


if __name__ == "__main__":
  unittest.main()
