"""Whole scaffold execution with actual DP, periodic prediction and shifts."""
from itertools import product
import unittest

from scaffold_galil import run, recognize, FPP_QUANTUM, OnlineGalil
from fpp_subroutine import build_marked_program
from dp_finite import build_dp_program


class ScaffoldGalilTest(unittest.TestCase):
  def test_quantum_cannot_overflow_finite_tape_cell_slots(self):
    # Max-plus path analysis includes infeasible branch combinations, making
    # this a static upper bound rather than a sample of observed executions.
    for program in (build_marked_program("abs"), build_dp_program("abs")):
      successors = []
      for row in program.code:
        successors.append(tuple(row[2].values()) if row[0] == "read" else
                          (row[3],) if row[0] in ("move", "write") else ())
      for tape in range(program.ntapes):
        counts = [0] * len(program.code)
        for _ in range(FPP_QUANTUM):
          counts = [int(row[0] == "move" and row[1] == tape)
                    + max((counts[q] for q in successors[i]), default=0)
                    for i, row in enumerate(program.code)]
        self.assertLessEqual(max(counts), 32)

  def test_fixed_recognizer_includes_epsilon(self):
    self.assertTrue(recognize(""))
    self.assertTrue(recognize("aba"))
    self.assertFalse(recognize("ab"))
    with self.assertRaises(ValueError):
      recognize("abc")

  def test_all_binary_prefixes_through_length_four(self):
    for n in range(5):
      for chars in product("ab", repeat=n):
        word = "".join(chars)
        output, _ = run(word)
        self.assertEqual(output, [int(word[:i] == word[:i][::-1])
                                 for i in range(1, n + 1)], word)

  def test_periodic_inputs_use_real_chain_shifts(self):
    for word in ("a" * 16, "ab" * 12, "abba" * 8):
      output, stats = run(word)
      self.assertEqual(output, [int(word[:i] == word[:i][::-1])
                               for i in range(1, len(word) + 1)], word)
      self.assertGreater(stats["chain_shifts"], 0, (word, stats))
      self.assertLess(stats["fpp_calls"], len(word), (word, stats))
      self.assertLessEqual(stats["radius"], 1024)
      self.assertLessEqual(stats["fields"], 1024)

  def test_outer_match_with_broken_period_restarts_search(self):
    word = "ab" + "a" * 20 + "ba"
    output, stats = run(word)
    self.assertEqual(output, [int(word[:i] == word[:i][::-1])
                             for i in range(1, len(word) + 1)])
    self.assertGreater(stats["search_restarts"], 0)

  def test_fixed_budget_executes_the_whole_controller(self):
    word, budget = "a" * 8, 2048
    output, stats = run(word, budget=budget)
    self.assertEqual(output, [1] * len(word))
    self.assertEqual(stats["steps"], budget * len(word))
    self.assertGreater(stats["chain_shifts"], 0)

  def test_periodic_breaks_resume_real_search_or_fallback(self):
    for word in ("a" * 12 + "b" + "a" * 12,
                 "ab" * 10 + "bbaa" + "ab" * 8,
                 "abba" * 6 + "bab" + "abba" * 5):
      output, _ = run(word)
      self.assertEqual(output, [int(word[:i] == word[:i][::-1])
                               for i in range(1, len(word) + 1)], word)


class OnlineGalilTest(unittest.TestCase):
  def drain_to_read(self, source, first):
    """Observe every output, including work after an answer was emitted."""
    outputs, events = [], dict.fromkeys(first.events, 0)
    result = first
    while True:
      if result.output is not None:
        outputs.append(result.output)
      for name, count in result.events.items():
        events[name] += count
      if result.input_ready:
        return outputs, events
      result = source.work()
      # Work has no input character, even after a positive answer. Input
      # heads must retain real arrival cells rather than use these work cells.
      self.assertIsNone(source.vm.top.label["input"])

  def place(self, source, head):
    """Test-only coordinates recovered from immutable stacks, never by A."""
    root = source.vm.top
    name = head + ".l"
    node = root.ptr[name + ".top"]
    creator, slot = root.label[name + ".tname"], root.label[name + ".tslot"]
    count = 0
    while node is not None:
      key = f"{creator}.{slot}."
      creator, slot = node.label[key + "bname"], node.label[key + "bslot"]
      node = node.ptr[key + "below"]
      count += 1
    return 2 * count - int(not root.label[head + ".gap"])

  def test_output_does_not_authorize_the_next_read(self):
    source = OnlineGalil()
    with self.assertRaisesRegex(ValueError, "waiting"):
      source.work()
    self.assertEqual(source.vm.t, -1)
    for char in ("", "ab", "c", None):
      with self.assertRaisesRegex(ValueError, "one binary"):
        source.read(char)
    self.assertEqual(source.vm.t, -1)

    result = source.read("a")
    self.assertEqual(result.output, 1)
    self.assertFalse(result.input_ready)
    before = source.vm.t
    with self.assertRaisesRegex(ValueError, "not ready"):
      source.read("b")
    self.assertEqual(source.vm.t, before)
    outputs, _ = self.drain_to_read(source, result)
    self.assertEqual(outputs, [1])
    self.assertTrue(source.input_ready)
    self.assertEqual(self.place(source, "C"), 2)
    self.assertEqual(self.place(source, "R"), 2)

  def test_pending_is_distinct_from_a_completed_negative_answer(self):
    source = OnlineGalil()
    self.drain_to_read(source, source.read("a"))
    result = source.read("b")
    self.assertIsNone(result.output)
    self.assertFalse(result.input_ready)
    before = source.vm.t
    with self.assertRaisesRegex(ValueError, "not ready"):
      source.read("a")
    self.assertEqual(source.vm.t, before)
    outputs, _ = self.drain_to_read(source, result)
    self.assertEqual(outputs, [0])
    outputs, _ = self.drain_to_read(source, source.read("a"))
    self.assertEqual(outputs, [1])

  def test_prefix_answers_and_proper_suffix_center_through_length_five(self):
    # The paper continues after a positive answer with the next tentative
    # center. At a read boundary this must be the longest proper palindromic
    # suffix (empty is allowed), not the just-reported whole prefix again.
    for n in range(6):
      for chars in product("ab", repeat=n):
        word, source = "".join(chars), OnlineGalil()
        for i, char in enumerate(word, 1):
          prefix = word[:i]
          outputs, _ = self.drain_to_read(source, source.read(char))
          self.assertEqual(outputs, [int(prefix == prefix[::-1])], prefix)
          size = max((m for m in range(1, i)
                      if prefix[-m:] == prefix[-m:][::-1]), default=0)
          self.assertEqual(self.place(source, "C"), 2 * i - size, prefix)
          self.assertEqual(self.place(source, "R"), 2 * i, prefix)
          self.assertEqual(self.place(source, "L"), 2 * (i - size), prefix)

  def test_chain_continues_after_reporting_without_the_next_input(self):
    source, shifts_after_output = OnlineGalil(), 0
    for _ in range(12):
      result, outputs, reported = source.read("a"), [], False
      while True:
        if result.output is not None:
          outputs.append(result.output)
          reported = True
        if reported:
          shifts_after_output += result.events["chain_shifts"]
        if result.input_ready:
          break
        result = source.work()
      self.assertEqual(outputs, [1])
    self.assertGreater(shifts_after_output, 0)

  def test_periodic_breaks_and_replay_preserve_each_output_event(self):
    for word in ("ab" * 12, "abba" * 8,
                 "ab" + "a" * 20 + "ba",
                 "a" * 12 + "b" + "a" * 12):
      source, totals = OnlineGalil(), {"chain_shifts": 0, "replays": 0}
      for i, char in enumerate(word, 1):
        outputs, events = self.drain_to_read(source, source.read(char))
        self.assertEqual(outputs, [int(word[:i] == word[:i][::-1])], word[:i])
        for name in totals:
          totals[name] += events[name]
      self.assertGreater(totals["chain_shifts"], 0, word)
      self.assertGreater(totals["replays"], 0, word)


if __name__ == "__main__":
  unittest.main()
