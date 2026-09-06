"""GS overlaps of u and reverse(u), without the separator mirror word.

Pattern and text heads have the same coordinate interval [0, len(u)] but
different read views. Copies preserve the view; moves change only position.
Proper overlaps are exactly palindrome prefixes shorter than u. This saves
the doubled preprocessing length of u # reverse(u).
"""
from gs_heads import HEADS, TESTS, HeadVM, border_controller, compile_controller, unit_moves
from gs_flag_heads import FLAG_BLIND


DUAL_HEADS = (*HEADS, "TextOrigin", "Lower", "Upper", "Cursor")


def dual_flag_controller(k=8):
  yield ("copy", "Cursor", "Upper")
  yield ("move", (("Cursor", -1),))
  if not (yield ("less", "Cursor", "Lower")):
    yield from border_controller(k, flags=True, tail_origin="TextOrigin")


def compile_dual_flags(k=8, *, unit=True):
  program = compile_controller(k, controller=dual_flag_controller)
  return unit_moves(program) if unit else program


class DualFlagVM(HeadVM):
  def __init__(self, word, lower, upper, program=None):
    if not 0 <= lower <= upper <= len(word):
      raise ValueError("the proper-prefix interval must satisfy 0 <= lower <= upper <= len(u)")
    super().__init__(word, compile_dual_flags() if program is None else program)
    self.positions.update(TextOrigin=0, Lower=lower, Upper=upper, Cursor=0)
    self.reverse = dict.fromkeys(DUAL_HEADS, False)
    self.reverse["OriginalEnd"] = self.reverse["TextOrigin"] = True
    self.flags = []

  def step(self):
    event, targets = self.program.code[self.state]
    op, *args = event
    if op == "symbols":
      values = []
      for head in args:
        position = self.positions[head]
        if head in FLAG_BLIND or not 0 <= position < len(self.word):
          raise AssertionError(("invalid oriented-head read", head, position, len(self.word)))
        values.append(self.word[len(self.word) - 1 - position if self.reverse[head] else position])
      self.comparisons += 1
      self.steps += 1
      self.state = targets[int(values[0] == values[1])]
    elif op == "copy":
      target, source = args
      self.positions[target] = self.positions[source]
      self.reverse[target] = self.reverse[source]
      self.steps += 1
      self.state = targets[0]
    elif op == "move":
      for head, delta in args[0]:
        self.positions[head] += delta
        self.motion += abs(delta)
        if head not in FLAG_BLIND and not 0 <= self.positions[head] <= len(self.word):
          raise AssertionError(("oriented head left its view", head, self.positions[head]))
      self.steps += 1
      self.state = targets[0]
    elif op == "flag":
      self.flags.append(args[0])
      self.steps += 1
      self.state = targets[0]
    else:
      super().step()

  def run(self, watchdog=None):
    super().run(watchdog)
    return tuple(self.flags)
