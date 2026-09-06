"""Output-side tests against independent language specifications."""
from itertools import product
import unittest

from phase_peg import Grammar, inverse_repeat
from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, NULL, symbol, old,
                              exists, negate, both, either, pointer, select,
                              read, edge, present)
from symbolic_sca2peg import share_expressions, Expr


from generate_scaffold_examples import marked_palindrome


class SymbolicScaffoldTest(unittest.TestCase):
  def test_shared_construction_and_streaming_preserve_the_emitted_grammar(self):
    expected = marked_palindrome().compile()
    with share_expressions():
      self.assertIs(symbol("a"), symbol("a"))
      with share_expressions():
        self.assertIs(symbol("b"), symbol("b"))
      actual = "\n".join(marked_palindrome().iter_rules()) + "\n"
    self.assertIsNone(Expr._pool)
    self.assertEqual(actual, expected)

  def test_marked_palindrome_has_real_push_pop_and_null_paths(self):
    machine = marked_palindrome()
    grammar = Grammar(machine.compile())
    for n in range(7):
      for chars in product("ab#", repeat=n):
        word = "".join(chars)
        pieces = word.split("#")
        expected = len(pieces) == 2 and pieces[0] == pieces[1][::-1]
        self.assertEqual(machine.run(word), expected, word)
        self.assertEqual(grammar.accepts(word[::-1]), expected, word)

  def test_direction_is_reversed_for_asymmetric_language(self):
    machine = Scaffold({"first_a": False, "seen": False, "out": False},
      {"first_a": either(old((), "first_a"),
                         both(negate(old((), "seen")), symbol("a"))),
       "seen": TRUE,
       "out": both(old((), "first_a"), symbol("b"))}, {}, "out")
    grammar = Grammar(machine.compile())
    self.assertTrue(machine.run("ab"))
    self.assertTrue(grammar.accepts("ba"))
    self.assertFalse(grammar.accepts("ab"))

  def test_selected_null_cannot_fall_back_to_live_pointer(self):
    machine = Scaffold({"out": False}, {"out": exists(("p",))},
                       {"p": select(symbol("a"), NULL, SELF)}, "out")
    grammar = Grammar(machine.compile())
    self.assertFalse(grammar.accepts("ba"))
    self.assertTrue(grammar.accepts("ab"))

  def test_self_edges_and_phase_inverse_preserve_even_parity(self):
    machine = Scaffold({"out": True}, {"out": negate(old((), "out"))},
                       {"self": SELF}, "out", "a")
    source = machine.compile()
    transformed = Grammar(inverse_repeat(source, 2))
    for n in range(8):
      self.assertEqual(Grammar(source).accepts("a" * n), n % 2 == 0)
      self.assertTrue(transformed.accepts("a" * n))

  def test_types_and_missing_fields_are_rejected(self):
    for expr in (pointer(()), old(("missing",), "out"), old((), "missing")):
      with self.assertRaises(ValueError):
        Scaffold({"out": False}, {"out": expr}, {}, "out")
    with self.assertRaises(ValueError):
      Scaffold({"out": False}, {"out": TRUE}, {"p": TRUE}, "out")

  def test_computed_pointer_queries_share_conditional_navigation(self):
    # Remember the previous a and b separately. Query the predecessor of
    # whichever remembered node is selected by the current input symbol.
    chosen = select(symbol("a"), pointer(("a",)), pointer(("b",)))
    target = edge(chosen, "previous")
    machine = Scaffold({"is_a": False, "out": False},
      {"is_a": symbol("a"), "out": both(present(target), read(target, "is_a"))},
      {"a": select(symbol("a"), SELF, pointer(("a",))),
       "b": select(symbol("b"), SELF, pointer(("b",))),
       "previous": pointer(())}, "out")
    grammar = Grammar(machine.compile())
    for n in range(8):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        index = word[:-1].rfind(word[-1]) if word else -1
        expected = index > 0 and word[index - 1] == "a"
        self.assertEqual(machine.run(word), expected, word)
        self.assertEqual(grammar.accepts(word[::-1]), expected, word)

  def test_new_node_queries_are_rejected_even_inside_pointer_choices(self):
    for target in (SELF, select(TRUE, NULL, SELF)):
      with self.assertRaisesRegex(ValueError, "old nodes"):
        Scaffold({"out": False}, {"out": read(target, "out")}, {}, "out")


if __name__ == "__main__":
  unittest.main()
