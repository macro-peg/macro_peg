"""Readonly input heads must clone and move without pointer-identity tests."""
import random
import unittest

from scavm import VM, SELF
from scavm_structs import Builder, emit
from scaffold_input import InputHead


class ScaffoldInputTest(unittest.TestCase):
  def exercise(self, size, seed):
    random.seed(seed)
    vm, text, positions = VM(), [], [0, 0, 0]
    for step in range(size):
      vm.begin()
      b = Builder()
      c = random.choice("ab")
      text.append(c)
      b.label["input"] = c
      heads = [InputHead(vm, vm.top, b, f"h{i}") for i in range(3)]
      for h in heads:
        h.append(SELF)
      # Include queue rotations, old-head clones and clones of newly
      # constructed cells before the new physical scaffold node is emitted.
      for _ in range(3):
        i = random.randrange(3)
        if random.random() < .2:
          j = random.randrange(3)
          heads[i].copy_from(heads[j])
          positions[i] = positions[j]
        elif random.random() < .55 and heads[i].can_right():
          heads[i].right()
          positions[i] += 1
        elif positions[i] > 0:
          heads[i].left()
          positions[i] -= 1
        self.assertEqual(heads[i].read(), None if positions[i] == 0 else text[positions[i]-1])
      for i, h in enumerate(heads):
        self.assertEqual(h.can_right(), positions[i] < len(text))
        h.finalize()
      emit(vm, b)
    return vm.stats()

  def test_cloning_and_motion_against_independent_position_oracle(self):
    for seed in range(5):
      self.exercise(200, seed)

  def test_old_heads_and_rotating_queues_remain_bounded(self):
    stats = self.exercise(3000, 18)
    self.assertLessEqual(stats["radius"], 426)
    self.assertLessEqual(stats["fields"], 432)

  def test_cloning_cannot_duplicate_a_partially_broadcast_arrival(self):
    vm = VM()
    vm.begin()
    b = Builder()
    b.label["input"] = "a"
    a, other = InputHead(vm, None, b, "a"), InputHead(vm, None, b, "b")
    a.append(SELF)
    with self.assertRaisesRegex(ValueError, "arrival"):
      other.copy_from(a)
    other.append(SELF)
    other.copy_from(a)
    with self.assertRaisesRegex(ValueError, "arrival"):
      other.append(SELF)
    other.right()
    self.assertFalse(other.can_right())

  def test_copies_require_the_same_scaffold_step(self):
    vm = VM()
    vm.begin()
    b = Builder()
    a = InputHead(vm, None, b, "a")
    other = InputHead(vm, None, Builder(), "b")
    with self.assertRaisesRegex(ValueError, "step"):
      a.copy_from(other)

  def test_origin_and_live_right_boundary(self):
    vm = VM()
    vm.begin()
    b = Builder()
    h = InputHead(vm, vm.top, b, "h")
    self.assertIsNone(h.read())
    self.assertFalse(h.can_right())
    with self.assertRaises(ValueError):
      h.left()
    with self.assertRaises(ValueError):
      h.right()
    b.label["input"] = "a"
    h.append(SELF)
    h.right()
    self.assertEqual(h.read(), "a")
    self.assertFalse(h.can_right())
    h.left()
    self.assertIsNone(h.read())
    h.right()
    self.assertEqual(h.read(), "a")
    h.finalize()
    emit(vm, b)


if __name__ == "__main__":
  unittest.main()
