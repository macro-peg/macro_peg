"""Sparse focus guards must compile without a product of tape alphabets."""
from itertools import product
import unittest

from tm2peg import tm_marked_palindrome
import symbolic_tm2peg as symbolic


class SymbolicCompilerTest(unittest.TestCase):
  def test_backspace_uses_the_grammar_parsers_unicode_escape(self):
    machine = symbolic.SymbolicTM(0, ["q"], "q", {"q"}, [
      symbolic.Transition("q", "\b", {}, "q", {})], "\b")
    self.assertIn('"\\u0008"', machine.compile())
    self.assertNotIn('"\\b"', machine.compile())

  def test_supplementary_and_surrogate_input_are_rejected(self):
    for char in ("😀", "\ud800", "\udfff"):
      with self.assertRaisesRegex(ValueError, "BMP"):
        symbolic.SymbolicTM(0, ["q"], "q", {"q"}, [], char)

  def test_sparse_machine_matches_dense_reference(self):
    dense = tm_marked_palindrome()
    sparse = symbolic.from_dense(dense, extra_tapes=18)
    for n in range(8):
      for word in product("ab#", repeat=n):
        value = "".join(word)
        self.assertEqual(sparse.run(value), dense.run(value), value)

  def test_compilation_size_does_not_expand_focus_vectors(self):
    sparse = symbolic.from_dense(tm_marked_palindrome(), extra_tapes=18)
    source = sparse.compile()
    self.assertLess(len(source), 150000)
    self.assertIn("D_0 =", source)
    self.assertNotIn("macro", source)

  def test_overlapping_guards_are_rejected(self):
    with self.assertRaisesRegex(ValueError, "overlapping"):
      symbolic.SymbolicTM(1, ["q"], "q", {"q"}, [
        symbolic.Transition("q", "a", {}, "q", {}),
        symbolic.Transition("q", "a", {0: "_"}, "q", {})], "ab")

  def test_unspecified_write_preserves_symbol_during_move(self):
    machine = symbolic.SymbolicTM(1, ["p", "q", "r", "s", "yes"], "p", {"yes"}, [
      symbolic.Transition("p", "a", {}, "q", {0: ("x", "S")}),
      symbolic.Transition("q", "a", {}, "r", {0: (None, "R")}),
      symbolic.Transition("r", "a", {}, "s", {0: (None, "L")}),
      symbolic.Transition("s", "a", {0: "x"}, "yes", {})], "a")
    self.assertTrue(machine.run("aaaa"))
    self.assertFalse(machine.run("aaa"))
    self.assertIn("Lsym_0", machine.compile())


if __name__ == "__main__":
  unittest.main()
