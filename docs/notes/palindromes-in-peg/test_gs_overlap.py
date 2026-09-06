import itertools
import random
import unittest

from gs_overlap import Meter, PalindromeView, decompose, iter_borders, iter_palindromic_prefixes


def words(alphabet, maximum):
  for size in range(maximum + 1):
    for symbols in itertools.product(alphabet, repeat=size):
      yield "".join(symbols)


def expected_borders(word):
  return [size for size in range(len(word) - 1, 0, -1) if word[:size] == word[-size:]]


class GsOverlapTest(unittest.TestCase):
  def test_borders_exhaustive(self):
    for word in words("ab", 12):
      self.assertEqual(list(iter_borders(word)), expected_borders(word), word)
    for word in words("abc", 7):
      self.assertEqual(list(iter_borders(word)), expected_borders(word), word)

  def test_palindrome_prefixes_exhaustive(self):
    for word in words("ab", 11):
      expected = [size for size in range(len(word), 0, -1) if word[:size] == word[:size][::-1]]
      self.assertEqual(list(iter_palindromic_prefixes(word)), expected, word)

  def test_nontrivial_decompositions_and_shortened_passes(self):
    samples = [("a" * run + "b") * repeats + "a" * run
               for run in range(4, 17) for repeats in (4, 5, 8)]
    nested = "a"
    for level in range(6):
      nested = nested * 4 + "bc"[level % 2]
      samples.extend((nested, nested[:-1], nested + nested[::-1]))
    multiple_stages = 0
    for word in samples:
      meter = Meter()
      self.assertEqual(list(iter_borders(word, meter=meter)), expected_borders(word), word[:80])
      multiple_stages += meter.stages > 1
      # A growth regression, not a replacement for the complexity argument or
      # a bound on native instructions. These cases trigger the deletion loop.
      self.assertLess(meter.comparisons + meter.events, 100 * len(word))
    self.assertGreater(multiple_stages, 20)

  def test_decomposition_contract(self):
    samples = list(words("ab", 8)) + [("a" * run + "b") * 8 for run in range(4, 13)]
    for k in (4, 5, 8):
      for word in samples:
        part = decompose(word, k=k)
        if not word:
          self.assertEqual(part.cut, 0)
          continue
        self.assertLess((k - 1) * part.cut, len(word), (word, k, part))
        suffix = word[part.cut:]
        periods = [period for period in range(1, len(suffix) // k + 1)
                   if suffix[:k * period] == suffix[:period] * k
                   and all(suffix[:period] != suffix[:divisor] * (period // divisor)
                           for divisor in range(1, period) if period % divisor == 0)]
        self.assertLessEqual(len(periods), 1, (word, k, part))
        self.assertEqual(part.period, periods[0] if periods else None)
        if part.period is not None:
          reach = part.period
          while reach < len(suffix) and suffix[reach] == suffix[reach - part.period]:
            reach += 1
          self.assertEqual(part.reach, reach)
        self.assertEqual(list(iter_borders(word, k=k)), expected_borders(word), (word, k))

  def test_long_and_random_inputs(self):
    rng = random.Random(603)
    samples = ["".join(rng.choice("abc") for _ in range(rng.randrange(1, 1025))) for _ in range(120)]
    samples.extend(("a" * 4096, "ab" * 2048, "aba" * 1365, "aaaab" * 819))
    for word in samples:
      self.assertEqual(list(iter_borders(word)), expected_borders(word), word[:80])

  def test_view_and_bounds(self):
    for word in ("", "a", "aba", "a#b"):
      view = PalindromeView(word)
      self.assertEqual([view[i] for i in range(len(view))], [*word, view.separator, *reversed(word)])
      with self.assertRaises(IndexError):
        _ = view[-1]
      with self.assertRaises(IndexError):
        _ = view[len(view)]
    for k in (0, 3, True, 4.0):
      with self.assertRaises(ValueError):
        list(iter_borders("aba", k=k))
    for size in (-1, 4, True):
      with self.assertRaises(ValueError):
        decompose("aba", size)


if __name__ == "__main__":
  unittest.main()
