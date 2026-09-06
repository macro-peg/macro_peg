"""Readonly Galil places: binary letters at odd places, virtual gaps at even.

There is no padded external input. A finite phase bit distinguishes a letter
from the gap immediately after it. InputHead still receives each real arrival
exactly once. A gap becomes available together with its preceding letter.
"""
from scaffold_input import InputHead


class PlaceHead:
  def __init__(self, vm, previous, builder, name):
    self.vm, self.builder, self.name = vm, builder, name
    self.head = InputHead(vm, previous, builder, name)
    self.gap = True if previous is None else vm.label(previous)[name + ".gap"]

  def append(self, cell):
    self.head.append(cell)

  def read(self):
    symbol = self.head.read()
    return None if symbol is None else "s" if self.gap else symbol

  def can_right(self):
    return not self.gap or self.head.can_right()

  def right(self):
    if self.gap:
      self.head.right()
    self.gap = not self.gap

  def left(self):
    if self.head.read() is None:
      raise ValueError("cannot move left of the input origin")
    if not self.gap:
      self.head.left()
    self.gap = not self.gap

  def is_first(self):
    return (not self.gap and not self.head.left_stack.empty()
            and self.head.left_stack.peek() is None)

  def copy_from(self, other):
    self.head.copy_from(other.head)
    self.gap = other.gap

  def finalize(self):
    self.head.finalize()
    self.builder.label[self.name + ".gap"] = self.gap
