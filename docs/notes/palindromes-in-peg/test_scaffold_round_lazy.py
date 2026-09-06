"""Language comparison with full packing, including an asymmetric source."""
from itertools import product
import unittest

from phase_peg import Grammar
from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, symbol, old,
                              pointer, select, read, edge, both)
from scaffold_round import pack_round
from scaffold_round_lazy import pack_round_lazy


class DemandRoundTest(unittest.TestCase):
  def test_queries_retain_all_needed_old_slots_and_drop_dead_fields(self):
    chosen = select(symbol("a"), pointer(("a",)), pointer(("b",)))
    source = Scaffold({"a": False, "out": False, "dead": True},
      {"a": symbol("a"), "out": read(edge(chosen, "previous"), "a"),
       "dead": old(("unused",), "dead")},
      {"a": select(symbol("a"), SELF, pointer(("a",))),
       "b": select(symbol("b"), SELF, pointer(("b",))),
       "previous": pointer(()), "unused": SELF}, "out")
    for width in (1, 2, 3, 4):
      full = pack_round([source] * width)
      lazy = pack_round_lazy([source] * width)
      self.assertFalse(any(key.endswith(".dead") for key in lazy.labels))
      self.assertFalse(any(key.endswith(".unused") for key in lazy.pointers))
      self.assertLess(len(lazy.labels), len(full.labels))
      grammar = Grammar(lazy.compile())
      for n in range(6):
        for chars in product("ab", repeat=n):
          word = "".join(chars)
          self.assertEqual(lazy.run(word), full.run(word), (width, word))
          self.assertEqual(grammar.accepts(word[::-1]), full.run(word), (width, word))

  def test_distinct_input_events_preserve_asymmetric_language(self):
    source = Scaffold({"out": True, "saved": False},
      {"out": both(symbol("."), old((), "saved")), "saved": symbol("a")},
      {}, "out", "ab.")
    mapping = [{"a": symbol("a"), "b": symbol("b"), ".": FALSE},
               {"a": FALSE, "b": FALSE, ".": TRUE}]
    lazy = pack_round_lazy([source] * 2, mapping, "ab")
    grammar = Grammar(lazy.compile())
    for word in ("", "a", "b", "ab", "ba", "aba", "bba"):
      self.assertEqual(lazy.run(word), not word or word[-1] == "a")
      self.assertEqual(grammar.accepts(word), not word or word[0] == "a")


if __name__ == "__main__":
  unittest.main()
