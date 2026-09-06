import itertools
import random
import unittest

from gs_events import IndexedJob, PatternMatcher, borders, decomposition, palindrome_job
from gs_overlap import decompose, iter_borders


def execute(job):
  outputs = []
  while not job.done:
    event = job.step()
    if event is not None and event[0] in ("flag", "border"):
      outputs.append(event[1])
  return outputs


class GsEventsTest(unittest.TestCase):
  def test_resumable_operations_match_direct_algorithm(self):
    for size in range(11):
      for letters in itertools.product("ab", repeat=size):
        word = "".join(letters)
        job = IndexedJob(word, decomposition(size))
        execute(job)
        self.assertEqual(job.result, decompose(word, k=8), word)
        self.assertLessEqual(job.steps, 42 * size + 1)
        job = IndexedJob(word, borders(size))
        self.assertEqual(execute(job), list(iter_borders(word, k=8)), word)
        self.assertLessEqual(job.steps, 107 * size + 1)

  def test_flag_intervals_include_epsilon_and_full_word(self):
    for word in ("", "a", "aba", "abba", "abab", "aaaaba", "ab" * 17):
      for lower in range(len(word) + 1):
        for upper in range(lower, len(word) + 2):
          job = palindrome_job(word, lower, upper)
          expected = [word[:size] == word[:size][::-1] for size in range(upper - 1, lower - 1, -1)]
          self.assertEqual(execute(job), expected, (word, lower, upper))
          self.assertLessEqual(job.steps, 215 * len(word) + 109)

  def test_pattern_matches_meet_indexed_clock(self):
    rng = random.Random(804)
    patterns = ["a", "ab", "aba", "a" * 31, ("a" * 16 + "b") * 16 + "a" * 16]
    patterns.extend("".join(rng.choice("ab") for _ in range(rng.randrange(1, 100))) for _ in range(50))
    for pattern in patterns:
      text = []
      matcher = PatternMatcher(pattern, text)
      stream = pattern * 3 + "aba" + pattern + pattern[:-1] + "b" + pattern * 2
      for index, char in enumerate(stream, 1):
        text.append(char)
        reported = False
        for _ in range(80):
          if matcher.waiting:
            break
          endpoint = matcher.step()
          if endpoint is not None:
            self.assertEqual(endpoint, index, (pattern, index, endpoint))
            reported = True
        self.assertEqual(reported, stream[:index].endswith(pattern), (pattern, index))

  def test_periodic_deletion_event_bounds(self):
    samples = [("a" * run + "b") * 32 + "a" * run for run in (8, 16, 32, 64)]
    nested = "a"
    for i in range(4):
      nested = nested * 8 + "bc"[i % 2]
      samples.extend((nested, nested + nested[::-1]))
    for word in samples:
      job = IndexedJob(word, decomposition(len(word)))
      execute(job)
      self.assertEqual(job.result, decompose(word, k=8))
      self.assertLessEqual(job.steps, 42 * len(word) + 1)
      job = IndexedJob(word, borders(len(word)))
      self.assertEqual(execute(job), list(iter_borders(word, k=8)))
      self.assertLessEqual(job.steps, 107 * len(word) + 1)


if __name__ == "__main__":
  unittest.main()
