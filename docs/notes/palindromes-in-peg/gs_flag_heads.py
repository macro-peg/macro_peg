"""All palindrome-prefix flags, with finite control and local head operations.

The input view is u # reverse(u). Upper/Lower are pre-established endpoint
heads; no integer lengths enter the generated control. The observer below
still preloads the view and endpoints and therefore is not a PAL SCA.
"""
from gs_heads import (HEADS, BLIND, TESTS, border_controller, compile_controller,
                      unit_moves, HeadVM)
from gs_overlap import PalindromeView


FLAG_HEADS = (*HEADS, "Lower", "Upper", "Cursor")
FLAG_BLIND = BLIND | {"Lower", "Upper", "Cursor"}


def flag_controller(k=8):
  yield ("copy", "Cursor", "Upper")
  yield ("move", (("Cursor", -1),))
  if not (yield ("less", "Cursor", "Lower")):
    yield from border_controller(k, flags=True)


def compile_flags(k=8):
  return unit_moves(compile_controller(k, controller=flag_controller))


class FlagVM(HeadVM):
  def __init__(self, word, lower, upper, program=None):
    if not 0 <= lower <= upper <= len(word) + 1:
      raise ValueError("invalid flag interval")
    super().__init__(PalindromeView(word), compile_flags() if program is None else program)
    self.positions.update(Lower=lower, Upper=upper, Cursor=0)
    self.flags = []

  def step(self):
    event, targets = self.program.code[self.state]
    if event[0] == "flag":
      self.flags.append(event[1])
      self.state = targets[0]
      self.steps += 1
    elif event[0] == "move" and any(head in FLAG_BLIND - BLIND for head, _ in event[1]):
      for head, delta in event[1]:
        self.positions[head] += delta
        self.motion += abs(delta)
      self.state = targets[0]
      self.steps += 1
    else:
      super().step()

  def run(self, watchdog=None):
    super().run(watchdog)
    return tuple(self.flags)
