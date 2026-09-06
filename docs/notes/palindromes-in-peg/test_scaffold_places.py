"""Galil's interleaved places are a view of unchanged binary input."""
import random
import unittest

from scavm import VM, SELF
from scavm_structs import Builder, emit
from scaffold_places import PlaceHead


class ScaffoldPlacesTest(unittest.TestCase):
  def test_moves_and_copies_across_virtual_spaces_and_live_input(self):
    rng = random.Random(935)
    vm, word, positions = VM(), "", [0, 0, 0]
    for tick in range(2000):
      vm.begin()
      b = Builder()
      heads = [PlaceHead(vm, vm.top, b, name) for name in ("c", "l", "r")]
      if tick % 3 == 0:
        symbol = rng.choice("ab")
        word += symbol
        b.label["input"] = symbol
        for head in heads:
          head.append(SELF)
      else:
        b.label["input"] = "a"
      i, j = rng.randrange(3), rng.randrange(3)
      if tick % 7 == 0:
        heads[i].copy_from(heads[j])
        positions[i] = positions[j]
      elif rng.randrange(2) and positions[i] < 2 * len(word):
        heads[i].right()
        positions[i] += 1
      elif positions[i]:
        heads[i].left()
        positions[i] -= 1
      for head, position in zip(heads, positions):
        expected = None if position == 0 else "s" if position % 2 == 0 else word[position // 2]
        self.assertEqual(head.read(), expected)
        self.assertEqual(head.can_right(), position < 2 * len(word))
        self.assertEqual(head.is_first(), position == 1)
        head.finalize()
      emit(vm, b)


if __name__ == "__main__":
  unittest.main()
