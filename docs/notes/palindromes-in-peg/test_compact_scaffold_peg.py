import itertools
from pathlib import Path
import tempfile
import unittest

from compact_scaffold_peg import compact
from phase_peg import Grammar


class CompactScaffoldPegTest(unittest.TestCase):
  def test_private_branches_keep_ordered_choice_and_predicates(self):
    source = '''S = E_0 !.;
E_0 = E_1 / E_2;
E_1 = E_3 E_4;
E_2 = E_5 E_6;
E_3 = &"a";
E_4 = "ab" / "a";
E_5 = !E_3;
E_6 = "b" / "E_999";
E_7 = "unused";
'''
    with tempfile.TemporaryDirectory() as folder:
      before, after = Path(folder) / "before.peg", Path(folder) / "after.peg"
      before.write_text(source)
      stats = compact(before, after)
      output = after.read_text()
      self.assertEqual(stats["fused"], 1)
      self.assertEqual(stats["after_rules"], 5)
      a, b = Grammar(source), Grammar(output)
      words = ["".join(w) for n in range(6) for w in itertools.product("ab", repeat=n)]
      for word in (*words, "E_999", "unused"):
        self.assertEqual(a.accepts(word), b.accepts(word), word)
      compact(before, after, short_names=True)
      shorter = Grammar(after.read_text())
      for word in (*words, "E_999", "unused"):
        self.assertEqual(a.accepts(word), shorter.accepts(word), word)
      stats = compact(before, after, short_names=True, inline_private=True)
      self.assertLess(stats["after_rules"], 5)
      inlined = Grammar(after.read_text())
      for word in (*words, "E_999", "unused"):
        self.assertEqual(a.accepts(word), inlined.accepts(word), word)

  def test_shared_branch_is_preserved(self):
    source = '''S = E_0 / E_1;
E_0 = E_1 / E_2;
E_1 = E_3 E_4;
E_2 = E_5 E_6;
E_3 = "a";
E_4 = "b";
E_5 = "b";
E_6 = "a";
'''
    with tempfile.TemporaryDirectory() as folder:
      before, after = Path(folder) / "before.peg", Path(folder) / "after.peg"
      before.write_text(source)
      self.assertEqual(compact(before, after)["fused"], 0)
      self.assertEqual(after.read_text(), source)

  def test_deep_private_chains_preserve_recursive_rule_boundaries(self):
    source = 'S = B_0 !.;\nB_0 = "a" B_0 / E_0;\n'
    source += ''.join(f'E_{n} = E_{n + 1};\n' for n in range(1000))
    source += 'E_1000 = "b" / "";\n'
    with tempfile.TemporaryDirectory() as folder:
      before, after = Path(folder) / "before.peg", Path(folder) / "after.peg"
      before.write_text(source)
      stats = compact(before, after, inline_private=True)
      self.assertLess(stats["after_rules"], 100)
      self.assertGreater(stats["after_rules"], 2)
      grammar = Grammar(after.read_text())
      for word in ("", "a", "aaaa", "b", "aaab", "ba", "abb", "c"):
        expected = word.rstrip("b").strip("a") == "" and word.count("b") <= 1
        self.assertEqual(grammar.accepts(word), expected, word)

  def test_inlining_keeps_sequence_scope_under_prefix_and_repetition(self):
    for operator, alias in itertools.product(("!", "&", "repeat"), (False, True)):
      body = "E_0* !." if operator == "repeat" else operator + "E_0 .* !."
      source = f'S = {body};\nE_0 = "a" "b";\n'
      if alias: source = f'S = {body};\nE_0 = E_1;\nE_1 = "a" "b";\n'
      with tempfile.TemporaryDirectory() as folder:
        before, after = Path(folder) / "before.peg", Path(folder) / "after.peg"
        before.write_text(source)
        compact(before, after, inline_private=True)
        original, inlined = Grammar(source), Grammar(after.read_text())
        for n in range(6):
          for chars in itertools.product("ab", repeat=n):
            word = "".join(chars)
            self.assertEqual(original.accepts(word), inlined.accepts(word), (operator, word))


if __name__ == "__main__":
  unittest.main()
