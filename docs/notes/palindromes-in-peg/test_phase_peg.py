"""Inverse fixed-width expansion must preserve ordered PEG decisions."""
from itertools import product
import unittest

from phase_peg import Grammar, inverse_repeat


class PhasePegTest(unittest.TestCase):
  def test_prefix_result_preserves_committed_choice_and_empty_success(self):
    for compact in (False, True):
      grammar = Grammar('S = "a" / "ab";')
      self.assertEqual(grammar.parse_prefix("ab", compact), 1)
      self.assertFalse(grammar.accepts("ab", compact))
      self.assertIsNone(grammar.parse_prefix("b", compact))
      self.assertEqual(Grammar('S = "";').parse_prefix("ab", compact), 0)

  def test_interpreter_handles_deep_rules_and_still_rejects_nullable_cycles(self):
    self.assertTrue(Grammar('S = A !.; A = "a" A / "";').accepts("a" * 5000))
    self.assertTrue(Grammar('S = A !.; A = "a" A / "";').accepts("a" * 5000, compact=True))
    with self.assertRaisesRegex(ValueError, "non-consuming recursion"):
      Grammar('S = S / "";').accepts("")
    with self.assertRaisesRegex(ValueError, "non-consuming recursion"):
      Grammar('S = S / "";').accepts("", compact=True)
    with self.assertRaisesRegex(ValueError, "nullable repetition"):
      Grammar('S = ""*;').accepts("")

  def compare(self, source, width, alphabet="ab", limit=5):
    original = Grammar(source)
    transformed = Grammar(inverse_repeat(source, width))
    for n in range(limit + 1):
      for chars in product(alphabet, repeat=n):
        word = "".join(chars)
        expanded = "".join(c * width for c in word)
        self.assertEqual(transformed.accepts(word), original.accepts(expanded), word)
        self.assertEqual(transformed.accepts(word, compact=True), original.accepts(expanded), word)

  def test_default_start_matches_interpreter_even_when_s_is_not_first(self):
    source = 'A = "aa"; S = "bb";'
    default = Grammar(inverse_repeat(source, 2))
    self.assertTrue(default.accepts("b"))
    self.assertFalse(default.accepts("a"))
    selected = Grammar(inverse_repeat(source, 2, start="A"))
    self.assertTrue(selected.accepts("a"))
    self.assertFalse(selected.accepts("b"))
    with self.assertRaisesRegex(ValueError, "start"):
      inverse_repeat(source, 2, start="Missing")

  def test_ordered_choice_cannot_retry_a_different_return_phase(self):
    source = 'S = ("a" / "aa") "bb" !.;'
    transformed = Grammar(inverse_repeat(source, 2))
    self.assertFalse(transformed.accepts("ab"))
    self.compare(source, 2)

  def test_phase_crossings_predicates_repetition_and_recursive_rules(self):
    sources = [
      'S = &A A !.; A = "a" A "b" / "";',
      'S = !("aaa") ("a" / "b")* !.;',
      'S = ("aa" / "a")* ("bb" / "b") !.;',
      'S = . A !.; A = "a" . / "bb" / "";',
      'S = ("a" / "aa")* !.;',
    ]
    for width in (1, 2, 3):
      for source in sources:
        self.compare(source, width)

  def test_repetition_cannot_give_back_a_virtual_character(self):
    for source in ('S = "a"* "aa" !.;', 'S = ("a" &"a")* "aa" !.;'):
      self.assertFalse(Grammar(inverse_repeat(source, 2)).accepts("a"))
      self.compare(source, 2)

  def test_sparse_tm_with_two_microsteps_per_character(self):
    from symbolic_tm2peg import SymbolicTM, Transition
    tm = SymbolicTM(1, ["p", "q", "r", "s", "yes"], "p", {"yes"}, [
      Transition("p", "a", {}, "q", {0: ("x", "S")}),
      Transition("q", "a", {}, "r", {0: (None, "R")}),
      Transition("r", "a", {}, "s", {0: (None, "L")}),
      Transition("s", "a", {0: "x"}, "yes", {})], "a")
    for width in (2, 4):
      transformed = Grammar(inverse_repeat(tm.compile(), width))
      for n in range(6):
        self.assertEqual(transformed.accepts("a" * n), n * width == 4)

  def test_invalid_width_and_non_bmp_literals_are_rejected(self):
    for width in (0, -1, True, 1.5):
      with self.assertRaises(ValueError):
        inverse_repeat('S = "";', width)
    with self.assertRaisesRegex(ValueError, "BMP"):
      inverse_repeat('S = "😀";', 2)


if __name__ == "__main__":
  unittest.main()
