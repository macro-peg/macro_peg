"""Compare exact event traces; identity leaves must add no source service."""
from itertools import product
import unittest

from scaffold_round_tree import pack_service_tree
from scaffold_event_buffer import pack_service
from symbolic_sca2peg import Scaffold, old, symbol, negate, either, both
from phase_peg import Grammar


class TreeRoundTest(unittest.TestCase):
  def test_non_power_of_two_round_keeps_exact_work_count(self):
    # Work toggles parity; a/b set the answer, then each work complements it.
    source = Scaffold({"out": True}, {"out": either(
      both(symbol("a"), negate(symbol("."))),
      both(symbol("."), negate(old((), "out"))))}, {}, "out", "ab.")
    for service in (1, 2, 3, 4, 7, 8):
      flat = pack_service(source, service)
      tree = pack_service_tree(source, service)
      grammar = Grammar(tree.compile())
      for n in range(5):
        for chars in product("ab", repeat=n):
          word = "".join(chars)
          self.assertEqual(tree.run(word), flat.run(word), (service, word))
          self.assertEqual(grammar.accepts(word[::-1]), flat.run(word), (service, word))


if __name__ == "__main__":
  unittest.main()
