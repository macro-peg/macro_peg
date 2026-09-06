"""Local FIFO service and one-node rounds for an independently known language."""
from collections import deque
from itertools import product
import unittest

from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, old, symbol, pointer,
                              read, both, either, negate, select)
from scaffold_event_buffer import buffer_source, pack_service
from phase_peg import Grammar


def source_fixture():
  # Read, compute/emit, prepare, read ... . The answer means the current
  # symbol is 'a'; a retained pointer supplies that symbol during compute.
  initial = dict(ready=True, busy=False, prepare=False, saved_a=False,
                 event=False, answer=False, fault=False)
  bad_event = either(both(old((), "ready"), symbol(".")),
                     both(negate(old((), "ready")), negate(symbol("."))))
  labels = {"ready": old((), "prepare"), "busy": old((), "ready"),
            "prepare": old((), "busy"),
            "saved_a": either(both(old((), "ready"), symbol("a")),
                               both(negate(old((), "ready")), old((), "saved_a"))),
            "event": old((), "busy"),
            "answer": both(old((), "busy"), read(pointer(("saved",)), "saved_a")),
            "fault": either(old((), "fault"), bad_event)}
  return Scaffold(initial, labels,
                   {"saved": select(old((), "ready"), SELF, pointer(("saved",)))},
                   "answer", "ab.")


class ScaffoldEventBufferTest(unittest.TestCase):
  def test_local_queue_service_matches_external_event_model(self):
    _, machine = buffer_source(source_fixture(), "ready", "event", "answer")
    for events in ("a...", "b...", "ab......", "aab.........",
                   "a" * 12 + "b" * 8 + "." * 70):
      root = machine.initial_node()
      pending, mode, saved, answer = deque(), "ready", None, True
      for char in events:
        if char != ".":
          pending.append(char)
          answer = False
        elif mode == "ready" and pending:
          saved, mode = pending.popleft(), "busy"
        elif mode == "busy":
          mode = "prepare"
          if not pending: answer = saved == "a"
        elif mode == "prepare":
          mode = "ready"
        root = machine.step(root, char)
        self.assertEqual(root.labels[machine.accepting], answer, events)
        self.assertFalse(root.labels["source.fault"], events)

  def test_packed_ordinary_peg_needs_only_the_original_letters(self):
    self.compare_packed(False)

  def test_demand_packing_keeps_delayed_outputs_and_cross_round_queue_cells(self):
    self.compare_packed(True)

  def compare_packed(self, lazy):
    _, wrapper = buffer_source(source_fixture(), "ready", "event", "answer")
    packed = pack_service(wrapper, 3, lazy=lazy)
    grammar = Grammar(packed.compile())
    self.assertEqual(packed.alphabet, tuple("ab"))
    for n in range(5):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        expected = not word or word[-1] == "a"
        self.assertEqual(packed.run(word), expected, word)
        self.assertEqual(grammar.accepts(word[::-1]), expected, word)


if __name__ == "__main__":
  unittest.main()
