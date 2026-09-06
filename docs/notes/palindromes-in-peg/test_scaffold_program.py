"""Finite tape instructions must have the same effect on the scaffold."""
import random
import unittest

from scavm import VM
from scavm_structs import Builder, emit
from scaffold_program import TapeView, ProgramView
from fpp_finite import BLANK, LEFT, END
from fpp_subroutine import build_marked_program, SOURCE, MARKS


class ScaffoldProgramTest(unittest.TestCase):
  def test_two_programs_are_isolated_and_can_drop_private_scratch(self):
    kernel = build_marked_program("ab")
    vm = VM()
    for step in range(3):
      vm.begin()
      b = Builder()
      first = ProgramView(vm, vm.top, b, kernel, name="f")
      second = ProgramView(vm, vm.top, b, kernel, name="d")
      if step == 0:
        first.tapes[SOURCE].write("a")
        first.tapes[SOURCE].move(1)
        first.tapes[SOURCE].write("b")
        second.tapes[SOURCE].write(LEFT)
        second.start()
      elif step == 1:
        first.reset()
        self.assertEqual(second.tapes[SOURCE].read(), LEFT)
        self.assertFalse(second.done)
      else:
        self.assertEqual(first.tapes[SOURCE].read(), BLANK)
        with self.assertRaises(ValueError):
          first.tapes[SOURCE].move(-1)
        self.assertTrue(first.done)
        self.assertEqual(second.tapes[SOURCE].read(), LEFT)
        self.assertFalse(second.done)
      first.finalize()
      second.finalize()
      emit(vm, b)

  def test_private_tape_matches_local_array_operations(self):
    random.seed(422)
    vm, data, head = VM(), {}, 0
    for step in range(1500):
      vm.begin()
      b = Builder()
      tape = TapeView(vm, vm.top, b, "t")
      self.assertEqual(tape.read(), data.get(head, BLANK))
      action = random.choice(("write", "left", "right"))
      if action == "write":
        symbol = random.choice("ab01_")
        tape.write(symbol)
        data[head] = symbol
      elif action == "right":
        tape.move(1)
        head += 1
      elif head > 0:
        tape.move(-1)
        head -= 1
      else:
        with self.assertRaises(ValueError):
          tape.move(-1)
      self.assertEqual(tape.read(), data.get(head, BLANK))
      tape.finalize()
      emit(vm, b)

  def test_fpp_table_runs_on_scaffold_private_tapes(self):
    kernel = build_marked_program("ab")
    for word in ("", "a", "abba", "ababa", "aababb", "a" * 31):
      vm = VM()
      # Fixture loading is explicit and creates scaffold nodes. The tested
      # finite program starts only after SOURCE has returned to its origin.
      operations = []
      for symbol in LEFT + word + END:
        operations.extend((("write", symbol), ("move", 1)))
      operations.extend(("move", -1) for _ in range(len(word) + 2))
      for op, value in operations:
        vm.begin()
        b = Builder()
        state = ProgramView(vm, vm.top, b, kernel)
        getattr(state.tapes[SOURCE], op)(value)
        state.finalize()
        emit(vm, b)
      started = False
      for _ in range(500 * (len(word) + 1)):
        vm.begin()
        b = Builder()
        state = ProgramView(vm, vm.top, b, kernel)
        if not started:
          state.start()
          started = True
        state.step()
        done = state.done
        state.finalize()
        emit(vm, b)
        if done:
          break
      else:
        self.fail("FPP exceeded its fixture watchdog")
      actual = []
      for _ in range(len(word)):
        vm.begin()
        b = Builder()
        state = ProgramView(vm, vm.top, b, kernel)
        state.tapes[MARKS].move(1)
        actual.append(int(state.tapes[MARKS].read()))
        state.finalize()
        emit(vm, b)
      self.assertEqual(actual, [int(word[:i] == word[:i][::-1]) for i in range(1, len(word)+1)])
      self.assertLess(vm.radius, 80)


if __name__ == "__main__":
  unittest.main()
