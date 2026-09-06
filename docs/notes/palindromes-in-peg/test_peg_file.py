from itertools import product
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from generate_scaffold_examples import marked_palindrome
from peg_file import FileGrammar
from phase_peg import Grammar


class FileGrammarTest(unittest.TestCase):
  def test_concrete_emitted_rules_match_without_loading_unvisited_rules(self):
    source = marked_palindrome().compile() + 'Unused = !"";\n'
    with TemporaryDirectory() as directory:
      path = Path(directory) / "emitted.peg"
      path.write_text(source)
      eager = Grammar(source)
      with FileGrammar(path) as lazy:
        self.assertEqual(len(lazy.rules), 0)
        for n in range(5):
          for letters in product("ab#", repeat=n):
            word = "".join(letters)
            self.assertEqual(lazy.accepts(word), eager.accepts(word), word)
        self.assertNotIn("Unused", lazy.rules)


if __name__ == "__main__": unittest.main()
