"""Whole online PAL control on the scaffold with a finite FPP fallback.

This connects matching, input head copies, source preparation, actual finite
FPP execution, candidate selection, repositioning and physical scratch reset.
No host coordinates, KMP arrays or node-identity tests enter the controller.

The chain optimization and Galil's real-time schedule are NOT implemented.
budget=None runs each arrival to completion and establishes online correctness;
a fixed budget creates exactly that many scaffold nodes per arrival but can
miss palindromes while behind. Thus this is not yet a PAL PEG or real-time PAL
recognizer. Its bounded instruction path is the integration target for chains.
"""
from scavm import VM, SELF
from scavm_structs import Builder, CounterView, emit
from scaffold_input import InputHead
from scaffold_program import ProgramView
from fpp_finite import LEFT, END, BLANK
from fpp_subroutine import build_marked_program, SOURCE, MARKS
from fpp_reuse import make_reusable

FIRST = "first:1"


def copy_counter(target, source):
  target.pos.copy_from(source.pos)
  target.neg.copy_from(source.neg)


def first_character(head):
  return not head.left_stack.empty() and head.left_stack.peek() is None


def run(word, budget=None):
  if any(c not in "ab" for c in word):
    raise ValueError("binary input required")
  if budget is not None and (type(budget) is not int or budget < 1):
    raise ValueError("positive integer budget required")
  kernel = make_reusable(build_marked_program("ab"))
  vm, outputs, costs, fpp_calls = VM(), [], [], 0
  for arrival in word:
    microsteps, caught = 0, False
    while (not caught if budget is None else microsteps < budget):
      vm.begin()
      b = Builder()
      b.label["input"] = arrival
      heads = [InputHead(vm, vm.top, b, name) for name in ("R", "L", "W")]
      right, left, window = heads
      if microsteps == 0:
        for head in heads:
          head.append(SELF)
      state = ProgramView(vm, vm.top, b, kernel)
      length = CounterView(vm, vm.top, b, "length")
      remaining = CounterView(vm, vm.top, b, "remain")
      if vm.top is None:
        mode, initialized, output = "idle", False, False
      else:
        label = vm.label(vm.top)
        mode, initialized, output = label["mode"], label["initialized"], label["output"]
      source, marks = state.tapes[SOURCE], state.tapes[MARKS]

      if mode == "idle":
        if right.can_right():
          right.right()
          if not initialized:
            left.copy_from(right)
            length.inc()
            initialized, output = True, True
          else:
            mode = "match"
      elif mode == "match":
        left.left()
        if left.read() == right.read():
          length.inc()
          length.inc()
          output, mode = first_character(left), "idle"
        else:
          window.copy_from(right)
          copy_counter(remaining, length)
          remaining.inc()
          source.write(LEFT)
          source.move(1)
          mode = "copy"
      elif mode == "copy":
        if remaining.sign() > 0:
          source.write(window.read())
          source.move(1)
          window.left()
          remaining.dec()
        else:
          source.write(END)
          mode = "source_home"
      elif mode == "source_home":
        if source.read() == LEFT:
          state.start()
          fpp_calls += 1  # observer only; never consulted by the controller
          mode = "fpp"
        else:
          source.move(-1)
      elif mode == "fpp":
        state.step()
        if state.done:
          mode = "mark_first"
      elif mode == "mark_first":
        marks.move(1)
        marks.write(FIRST)
        marks.move(1)
        mode = "marks_end"
      elif mode == "marks_end":
        if marks.read() == END:
          marks.move(-1)
          mode = "choose"
        else:
          marks.move(1)
      elif mode == "choose":
        if marks.read() in ("1", FIRST):
          left.copy_from(right)
          length.reset()
          length.inc()
          mode = "rewind_choice"
        else:
          marks.move(-1)
      elif mode == "rewind_choice":
        if marks.read() == FIRST:
          marks.write("1")
          state.start(kernel.cleanup)
          mode = "cleanup"
        else:
          marks.move(-1)
          left.left()
          length.inc()
      elif mode == "cleanup":
        state.step()
        if state.done:
          mode = "clear_begin"
      elif mode == "clear_begin":
        source.move(1)
        mode = "clear"
      elif mode == "clear":
        if source.read() == BLANK:
          source.move(-1)
          mode = "clear_back"
        else:
          source.write(BLANK)
          source.move(1)
      elif mode == "clear_back":
        if source.read() == LEFT:
          source.write(BLANK)
          output, mode = first_character(left), "idle"
        else:
          source.move(-1)
      else:
        raise AssertionError(mode)

      caught = mode == "idle" and not right.can_right()
      report = int(output) if caught else 0
      for head in heads:
        head.finalize()
      state.finalize()
      length.finalize()
      remaining.finalize()
      b.label.update(mode=mode, initialized=initialized, output=output)
      emit(vm, b)
      microsteps += 1
    outputs.append(report)
    costs.append(microsteps)
  return outputs, {**vm.stats(), "max_microsteps": max(costs, default=0),
                   "fpp_calls": fpp_calls, "costs": costs}
