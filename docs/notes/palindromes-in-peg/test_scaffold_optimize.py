"""Constant propagation must preserve sentinel, null, and recursive behavior."""
from itertools import product
import unittest

from scaffold_optimize import optimize
from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, NULL, old, pointer,
                              read, edge, present, negate, both, either, symbol)
from phase_peg import Grammar


class ScaffoldOptimizeTest(unittest.TestCase):
  def test_nulls_and_constant_labels_propagate_before_field_projection(self):
    source = Scaffold({"out": False, "off": False, "propagate": False, "on": True},
      {"off": FALSE, "propagate": old((), "off"), "on": TRUE,
       "out": either(both(negate(old((), "propagate")), symbol("a")),
                     read(edge(pointer(()), "null"), "on"))},
      {"null": NULL, "dead": pointer(("null",))}, "out")
    reduced, stats = optimize(source)
    self.assertGreater(stats["rounds"], 1)
    self.assertEqual(set(reduced.labels), {"out"})
    self.assertEqual(reduced.pointers, {})
    self.compare(source, reduced)

  def test_initial_value_and_sentinel_pointer_are_not_erased(self):
    source = Scaffold({"out": True, "first": True, "on": True},
      {"first": FALSE, "on": TRUE,
       "out": either(old((), "first"), both(symbol("a"), read(pointer(("p",)), "on")))},
      {"p": SELF}, "out")
    reduced, _ = optimize(source, roots=("first",))
    self.assertIn("first", reduced.labels)
    self.assertIn("p", reduced.pointers)
    self.compare(source, reduced)

  def compare(self, source, reduced):
    grammar = Grammar(reduced.compile())
    for n in range(6):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        self.assertEqual(reduced.run(word), source.run(word), word)
        self.assertEqual(grammar.accepts(word[::-1]), source.run(word), word)


if __name__ == "__main__":
  unittest.main()
