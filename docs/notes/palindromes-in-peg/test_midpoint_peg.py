"""Check exact returned positions, not just acceptance of a suffix consumer."""
from itertools import product
import unittest

from midpoint_peg import build, grammar
from phase_peg import Grammar


class MidpointPegTest(unittest.TestCase):
  def test_fifo_focus_names_exact_middle_node_through_rotations(self):
    circuit, machine = build()
    nodes = [machine.initial_node()]
    fault = circuit._label("circuit.fault", 0)
    for n in range(1, 1025):
      node = machine.step(nodes[-1], "ab"[n % 2])
      nodes.append(node)
      self.assertIs(node.pointers["half"], nodes[n // 2], n)
      self.assertIs(node.pointers["upper_half"], nodes[(n + 1) // 2], n)
      self.assertFalse(node.labels[fault], n)

  def test_emitted_peg_returns_half_of_arbitrary_binary_suffixes(self):
    source = grammar()
    midpoint = Grammar(source, start="H")
    lower = Grammar(source, start="HalfFloor")
    for n in range(7):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        self.assertEqual(midpoint.parse_prefix(word), (n + 1) // 2, word)
        self.assertEqual(lower.parse_prefix(word), n // 2, word)
    # Odd lengths and queue/power boundaries are essential; power-of-two
    # palindrome grammars do not establish this midpoint contract.
    for n in (15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257):
      word = ("abbaba" * (n // 6 + 1))[:n]
      self.assertEqual(midpoint.parse_prefix(word, compact=True), (n + 1) // 2, n)
      self.assertEqual(lower.parse_prefix(word, compact=True), n // 2, n)
    with_prefix = Grammar(source + '\nProbe = "ab" H;\n', start="Probe")
    for n in range(8):
      self.assertEqual(with_prefix.parse_prefix("ab" + "a" * n), 2 + (n + 1) // 2)
    self.assertTrue(Grammar(source).accepts("ab"))  # S is deliberately not PAL.


if __name__ == "__main__":
  unittest.main()
