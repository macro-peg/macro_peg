import itertools
import random
import unittest

from gs_heads import compile_controller, unit_moves, HeadVM, HEADS
from gs_overlap import iter_borders


class GSHeadsTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    cls.program = compile_controller()
    cls.unit = unit_moves(cls.program)

  def test_complete_finite_table_and_unit_lowering(self):
    self.assertLess(len(self.program.code), 1200)
    for event, targets in self.unit.code:
      for target in targets:
        self.assertIn(target, range(len(self.unit.code)))
      if event[0] == "move":
        self.assertEqual(len(event[1]), 1)
        self.assertIn(event[1][0][1], (-1, 1))

  def test_all_borders_exhaustive_and_periodic(self):
    samples = ["".join(w) for n in range(10) for w in itertools.product("ab", repeat=n)]
    rng = random.Random(909)
    samples += ["".join(rng.choice("ab#") for _ in range(rng.randrange(1, 500))) for _ in range(35)]
    samples += ["a" * 1024, "ab" * 513, ("a" * 8 + "b") * 37,
                ("a" * 8 + "b" + "a" * 8 + "c") * 21]
    for word in samples:
      expected = tuple(iter_borders(word, k=8))
      with self.subTest(word=word[:50], length=len(word)):
        self.assertEqual(HeadVM(word, self.program).run(), expected)
        self.assertEqual(HeadVM(word, self.unit).run(), expected)


if __name__ == "__main__":
  unittest.main()
