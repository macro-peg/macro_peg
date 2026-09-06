"""Compare local doubling search with the defining suffix-palindrome test."""
from itertools import product
import json
from pathlib import Path
import unittest

from fpp_finite import Program, LEFT, END, BLANK
from dp_finite import LOWER, OUTPUT
import dp_search_finite as search


def execute(p, prefix, suffix="", lower=0):
  tapes = [{} for _ in range(p.ntapes)]
  tokens = [LEFT, *prefix, *suffix, END]
  center = len(prefix)
  tokens[center] = search.center_symbol(tokens[center])
  tapes[search.WINDOW] = dict(enumerate(tokens))
  tapes[LOWER] = dict(enumerate(LEFT + "1" * lower + END))
  positions = [0] * p.ntapes
  positions[search.WINDOW] = center
  result = p.execute(tapes, positions, 3000 * (len(prefix) + lower + 1))
  h = None
  if result.state == p.found:
    h = sum(v == "1" for v in tapes[OUTPUT].values())
  return h, result, tokens


def expected(prefix, lower):
  return next((h for h in range(lower + 1, (len(prefix) - 1) // 4 + 1)
               if prefix[-(2*h+1):] == prefix[-(2*h+1):][::-1]
               and prefix[-(4*h+1):] == prefix[-(4*h+1):][::-1]), None)


class SearchTest(unittest.TestCase):
  def test_saved_table_without_builder(self):
    path = Path(__file__).parent / "generated" / "dp-search-controller.json"
    data = json.loads(path.read_text())
    p = Program(data["alphabet"], data["ntapes"])
    p.code, p.start = data["code"], data["start"]
    p.found, p.missed = data["found"], data["missed"]
    p.validate()
    for prefix, lower in [("asasa", 0), ("as" * 35, 3),
                          ("aabasbbbs" * 12, 0), ("b", 1)]:
      self.check_search(p, prefix, "babas", lower)

  def check_search(self, p, prefix, suffix, lower):
    h, result, original = execute(p, prefix, suffix, lower)
    self.assertEqual(h, expected(prefix, lower), (prefix, lower))
    self.assertEqual(result.state, p.found if h is not None else p.missed)
    self.assertEqual(result.positions[search.WINDOW], len(prefix))
    self.assertEqual(result.tapes[LOWER], dict(enumerate(LEFT + "1" * lower + END)))
    want = dict(enumerate(original))
    if h is not None:
      for multiple in range(1, 5):
        index = len(prefix) - multiple * h
        want[index] = search.period_symbol(want[index])
      self.assertEqual(result.positions[OUTPUT], 0)
    self.assertEqual({i: v for i, v in result.tapes[search.WINDOW].items()
                      if v != BLANK}, want)

  def test_all_short_binary_prefixes_and_lower_bounds(self):
    p = search.build_search_program("ab")
    for n in range(1, 8):
      for word in product("ab", repeat=n):
        for lower in (0, 1, n):
          self.check_search(p, "".join(word), "baba", lower)

  def test_places_final_boundary_and_multiple_doublings(self):
    p = search.build_search_program()
    words = ["a", "aaaaa", "aab" * 20, "aaaab" * 12,
             "a" * 65, "ababbb" * 20, "aababbabbbab"]
    for word in words:
      places = "s".join(word)
      for prefix in (places, places + "s"):
        for lower in (0, 1, 3, len(prefix)):
          self.check_search(p, prefix, "sbasa", lower)

  def test_first_answer_after_multiple_doublings(self):
    p = search.build_search_program("ab")
    for h, root in [(3, "aabb"), (5, "abbbab"), (9, "abbababbaa")]:
      word = "".join(root[min(j % (2*h), 2*h - j % (2*h))]
                     for j in range(4*h + 1))
      self.assertEqual(expected(word, 0), h)
      self.check_search(p, word, "ba", 0)

  def test_window_access_stays_local_to_distant_center(self):
    class GuardedWindow(dict):
      def get(self, key, default=None):
        if not self.center - 9 <= key <= self.center:
          raise AssertionError(("nonlocal read", key, self.center))
        return super().get(key, default)

      def __setitem__(self, key, value):
        if not self.center - 9 <= key <= self.center:
          raise AssertionError(("nonlocal write", key, self.center))
        super().__setitem__(key, value)

    p = search.build_search_program("ab")
    counts = []
    for center in (19, 1009, 100009):
      window = GuardedWindow({i: "a" for i in range(center - 9, center)})
      window.center = center
      window[center] = search.center_symbol("a")
      tapes = [{} for _ in range(p.ntapes)]
      tapes[search.WINDOW], tapes[LOWER] = window, {0: LEFT, 1: END}
      positions = [0] * p.ntapes
      positions[search.WINDOW] = center
      result = p.execute(tapes, positions, 5000)
      self.assertEqual(result.state, p.found)
      self.assertEqual(result.positions[search.WINDOW], center)
      self.assertEqual(sum(v == "1" for v in tapes[OUTPUT].values()), 1)
      counts.append(result.steps)
    self.assertEqual(len(set(counts)), 1)


if __name__ == "__main__":
  unittest.main()
