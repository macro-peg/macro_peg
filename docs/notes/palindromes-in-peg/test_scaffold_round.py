"""Check complete reachable configurations and the ordinary PEG output."""
from itertools import product
import unittest

from phase_peg import Grammar
from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, NULL, symbol, old,
                              pointer, select, read, edge, present, both, either, negate)
from scaffold_round import RoundBuilder, pack_round


class ScaffoldRoundTest(unittest.TestCase):
  def compare_graph(self, source, original, packed, slot, width):
    pending, seen = [(original, packed, slot)], set()
    while pending:
      original, physical, slot = pending.pop()
      key = id(original), id(physical), slot
      if key in seen: continue
      seen.add(key)
      self.assertEqual(original is None, physical is None)
      if original is None: continue
      for label, expected in original.labels.items():
        self.assertEqual(physical.labels[RoundBuilder.label(slot, label)], expected,
                         (slot, label))
      for field, target in original.pointers.items():
        physical_target = physical.pointers[RoundBuilder.field(slot, field)]
        target_slot = sum(1 << bit for bit in range((width - 1).bit_length())
                          if physical.labels[RoundBuilder.tag_bit(slot, field, bit)])
        pending.append((target, physical_target, target_slot))

  def test_self_null_conditional_and_cross_round_references(self):
    chosen = select(symbol("a"), pointer(("a",)), pointer(("b",)))
    target = edge(chosen, "previous")
    source = Scaffold({"is_a": False, "out": False},
      {"is_a": symbol("a"), "out": both(present(target), read(target, "is_a"))},
      {"a": select(symbol("a"), SELF, pointer(("a",))),
       "b": select(symbol("b"), SELF, pointer(("b",))),
       "previous": pointer(()), "loop": SELF,
       "optional": select(symbol("a"), NULL, pointer(("optional",)))}, "out")
    for width in (1, 2, 3, 4):
      packed = pack_round([source] * width)
      grammar = Grammar(packed.compile())
      for n in range(6):
        for chars in product("ab", repeat=n):
          word = "".join(chars)
          original, physical = source.initial_node(), packed.initial_node()
          for char in word:
            for _ in range(width): original = source.step(original, char)
            physical = packed.step(physical, char)
            self.compare_graph(source, original, physical, width - 1, width)
          self.assertEqual(grammar.accepts(word[::-1]), original.labels["out"], word)

  def test_distinct_stages_and_asymmetric_language(self):
    initial = {"first_a": False, "seen": False, "out": False}
    first = Scaffold(initial,
      {"first_a": either(old((), "first_a"), both(negate(old((), "seen")), symbol("a"))),
       "seen": TRUE, "out": old((), "out")}, {"previous": pointer(())}, "out")
    second = Scaffold(initial,
      {"first_a": old((), "first_a"), "seen": old((), "seen"),
       "out": both(old((), "first_a"), symbol("b"))},
      {"previous": pointer(())}, "out")
    packed = pack_round([first, second])
    grammar = Grammar(packed.compile())
    self.assertTrue(packed.run("ab"))
    self.assertFalse(packed.run("ba"))
    self.assertTrue(grammar.accepts("ba"))
    self.assertFalse(grammar.accepts("ab"))

  def test_invalid_round_schema_is_rejected(self):
    with self.assertRaises(ValueError): pack_round([])
    a = Scaffold({"out": False}, {"out": TRUE}, {}, "out")
    b = Scaffold({"out": False}, {"out": TRUE}, {"p": NULL}, "out")
    with self.assertRaises(ValueError): pack_round([a, b])

  def test_constant_event_guards_skip_disabled_source_queries(self):
    source = Scaffold({"out": False},
      {"out": both(symbol("a"), old((), "out"))},
      {"p": select(symbol("a"), edge(pointer(()), "p"), SELF)}, "out")
    builder = RoundBuilder([source], [{"a": FALSE, "b": TRUE}])
    from unittest.mock import patch
    with patch.object(builder, "read", side_effect=AssertionError("disabled read")), \
         patch.object(builder, "follow", side_effect=AssertionError("disabled edge")):
      packed = builder.build()
    self.assertFalse(packed.run("ab"))


if __name__ == "__main__":
  unittest.main()
