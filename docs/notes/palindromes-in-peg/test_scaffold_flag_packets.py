import random
import unittest

from phase_peg import Grammar
from scaffold_flag_packets import fixture
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar


def decode(circuit, root, view):
  node = root.pointers[view.root_key]
  index = scalar(circuit, root, view.index_key)
  result = []
  while node is not None:
    bit, previous = view.pool.fields(index)
    result.append(scalar(circuit, node, bit))
    index = scalar(circuit, node, previous)
    if index == -1:
      index = scalar(circuit, node, view.pool.index_key)
      node = node.pointers[view.pool.back_key]
  return result


def apply(stacks, char, capacity):
  x, y = stacks
  answer = False
  if char == "!": x.clear()
  elif char == "c": y[:] = x
  elif char == "r": x[:] = y
  elif char == "<" and x: answer = x.pop(0)
  elif char == ">" and y: answer = y.pop(0)
  elif char in "ab":
    for slot in range(capacity):
      if char == "a" or slot % 3 != 1:
        x.insert(0, (char == "a") if slot % 2 == 0 else (char == "b"))
  return answer


class FlagPacketsTest(unittest.TestCase):
  def test_sparse_pushes_packet_boundaries_and_immutable_copies(self):
    with share_expressions():
      circuit, _, views, machine = fixture()
    rng = random.Random(919)
    word = "abca" + "<" * 30 + ">" * 20 + "br<ac" + ">" * 20 + "!r" + \
           "".join(rng.choice("ab<>cr!") for _ in range(250))
    root, stacks = machine.initial_node(), [[], []]
    for index, char in enumerate(word):
      expected = apply(stacks, char, 7)
      root = machine.step(root, char)
      self.assertFalse(scalar(circuit, root, "circuit.fault"), (index, char))
      self.assertEqual(root.labels[machine.accepting], expected, (index, char))
      for view, wanted in zip(views, stacks):
        self.assertEqual(decode(circuit, root, view), wanted, (index, char, view.name))

  def test_ordinary_peg_on_original_commands(self):
    with share_expressions():
      _, _, _, machine = fixture(5)
    grammar = Grammar(machine.compile())
    rng = random.Random(920)
    words = ["", "a<", "b<", "ac<>", "bc" + "<" * 7 + ">", "aac!r<"]
    words += ["".join(rng.choice("ab<>cr!") for _ in range(40)) + "<" for _ in range(40)]
    for word in words:
      stacks, expected = [[], []], False
      for char in word:
        expected = apply(stacks, char, 5)
      self.assertEqual(grammar.accepts(word[::-1]), expected, word)


if __name__ == "__main__":
  unittest.main()
