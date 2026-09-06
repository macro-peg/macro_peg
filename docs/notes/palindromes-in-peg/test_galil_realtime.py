"""FIFO deadline and source event tests, including postponed negatives."""
from itertools import product
import unittest

from scaffold_galil import OnlineStep
from galil_realtime import BufferedSource, RealtimeGalil


class ScriptedSource:
  """Test source with explicit read, emit and post-output preparation."""
  def __init__(self, script):
    self.script = iter(script)
    self.input_ready, self.preparation = True, 0

  def read(self, char):
    assert self.input_ready
    self.remaining, self.answer, self.after = next(self.script)
    self.input_ready = False
    return self.work()

  def work(self):
    assert not self.input_ready
    output = None
    if self.preparation:
      self.preparation -= 1
      self.input_ready = self.preparation == 0
    else:
      self.remaining -= 1
      if self.remaining == 0:
        output = self.answer
        self.preparation = self.after
        self.input_ready = not self.preparation
    return OnlineStep(output, self.input_ready, {})


class RealtimeGalilTest(unittest.TestCase):
  def test_fifo_may_postpone_negative_answers_without_missing_positive(self):
    # c=3, k=(0,2,1,0,0), d=(1,10,2,1,1) meet the predictability
    # inequalities. The second output is unavailable at its own deadline.
    source = ScriptedSource([(1, 1, 0), (10, 0, 0), (2, 0, 0), (1, 0, 0), (1, 1, 0)])
    machine = BufferedSource(source, 6)
    observed, completed = [], []
    for char in "ababa":
      observed.append(machine.read(char))
      completed.append(machine.last_completed)
    self.assertEqual(observed, [1, 0, 0, 0, 1])
    self.assertFalse(completed[1])
    self.assertEqual(machine.outputs, 5)

  def test_completed_answer_survives_post_output_preparation(self):
    source = ScriptedSource([(1, 1, 3), (1, 0, 0)])
    machine = BufferedSource(source, 2)
    self.assertEqual(machine.read("a"), 1)
    self.assertTrue(machine.last_completed)
    self.assertFalse(source.input_ready)
    self.assertEqual(machine.read("b"), 0)
    self.assertFalse(machine.last_completed)
    self.assertEqual(machine.reads, 1)
    self.assertEqual(tuple(machine.pending), ("b",))

  def test_derived_schedule_on_binary_prefixes_and_periodic_breaks(self):
    words = ["".join(chars) for n in range(5) for chars in product("ab", repeat=n)]
    words += ["a" * 16, "ab" * 12, "abba" * 8,
              "ab" + "a" * 20 + "ba", "a" * 12 + "b" + "a" * 12]
    for word in words:
      machine = RealtimeGalil()
      self.assertTrue(machine.accepts_empty())
      actual = [machine.read(char) for char in word]
      expected = [int(word[:i] == word[:i][::-1]) for i in range(1, len(word) + 1)]
      self.assertEqual(actual, expected, word)


if __name__ == "__main__":
  unittest.main()
