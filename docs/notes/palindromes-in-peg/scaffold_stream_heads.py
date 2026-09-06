"""Clonable stream heads using the explicitly scheduled queue kernel.

A right move consumes a front cell at an operation boundary and requests
three subsequent maintenance instructions if it used a queue. The caller
must schedule those before another public operation on that queue.
"""
from scaffold_circuit import (Value, Ref, PREVIOUS, EMPTY, TRUE, FALSE,
                              conjunction, disjunction, neg, choose)
from scaffold_circuit_structs import Stack, StackPool
from scaffold_queue_registers import QueueRegisters


class StreamBank:
  def __init__(self, circuit, names):
    self.circuit = circuit
    self.cells = StackPool(circuit, {"cells": 1})
    self.queues = QueueRegisters(circuit, tuple(name + ".in" for name in names))
    self.heads = {name: StreamHead(self, name) for name in names}

  def finalize(self):
    for head in self.heads.values():
      head.finalize()
    self.queues.finalize()


class StreamHead:
  def __init__(self, bank, name):
    self.circuit, self.name = bank.circuit, name
    self.focus_key = name + ".focus"
    self.focus = self.circuit.get_ref(PREVIOUS, self.focus_key)
    self.left = Stack(bank.cells, name + ".l", "cells")
    self.right = Stack(bank.cells, name + ".r", "cells")
    self.queue = bank.queues.queues[name + ".in"]

  def can_right(self):
    return disjunction(neg(self.right.empty()), neg(self.queue.empty()))

  def peek_right(self, enabled=TRUE):
    from_stack = neg(self.right.empty())
    a, _ = self.right.peek()
    b, _ = self.queue.s["F"].peek()
    self.circuit.require(neg(self.queue.s["F"].empty()),
                         conjunction(enabled, neg(from_stack), neg(self.queue.empty())))
    return Ref.select(from_stack, a, b)

  def move_right(self, enabled):
    self.circuit.require(self.can_right(), enabled)
    self.left.push(self.focus, enabled=enabled, slot=0)
    from_stack = neg(self.right.empty())
    a, _ = self.right.pop(conjunction(enabled, from_stack))
    maintain = conjunction(enabled, neg(from_stack))
    b = self.queue.pop(maintain)
    self.focus = Ref.select(enabled, Ref.select(from_stack, a, b), self.focus)
    return maintain

  def move_left(self, enabled):
    self.circuit.require(neg(self.left.empty()), enabled)
    self.right.push(self.focus, enabled=enabled, slot=0)
    value, _ = self.left.pop(enabled)
    self.focus = Ref.select(enabled, value, self.focus)

  def follow_arrival(self, cell, enabled):
    """The global end head advances to a newly arrived cell directly."""
    self.left.push(self.focus, enabled=enabled, slot=0)
    self.focus = Ref.select(enabled, cell, self.focus)

  def copy_from(self, other, enabled):
    self.focus = Ref.select(enabled, other.focus, self.focus)
    self.left.copy_from(other.left, enabled)
    self.right.copy_from(other.right, enabled)
    self.queue.copy_from(other.queue, enabled)

  def reset(self, enabled):
    self.focus = Ref.select(enabled, EMPTY, self.focus)
    self.left.clear(enabled)
    self.right.clear(enabled)
    self.queue.clear(enabled)

  def finalize(self):
    self.left.finalize()
    self.right.finalize()
    self.circuit.put_ref(self.focus_key, self.focus)


class PatternTextHead:
  def __init__(self, bank, name):
    self.circuit, self.name = bank.circuit, name
    self.pattern = bank.heads[name + ".pattern"]
    self.text = bank.heads[name + ".text"]
    self.mode_key = name + ".text_mode"
    self.text_mode = self.circuit.get(PREVIOUS, self.mode_key, (False, True), False).eq(True)

  def available(self):
    return choose(self.text_mode, self.text.can_right(), self.pattern.focus.present())

  def read(self, enabled=TRUE):
    target = Ref.select(self.text_mode,
                        self.text.peek_right(conjunction(enabled, self.text_mode)), self.pattern.focus)
    value = self.circuit.get(target, "input", tuple("ab"), "a")
    return Value.select(target.present(), value, Value.constant(None))

  def start(self, snapshot, enabled):
    self.pattern.copy_from(snapshot, enabled)
    self.text.reset(enabled)
    self.text_mode = choose(enabled, neg(snapshot.focus.present()), self.text_mode)

  def move(self, direction, enabled):
    mode = self.text_mode
    if direction == 1:
      self.pattern.move_left(conjunction(enabled, neg(mode)))
      requested = self.text.move_right(conjunction(enabled, mode))
      self.text_mode = disjunction(mode, conjunction(enabled, neg(mode), neg(self.pattern.focus.present())))
      return self.text.queue.name, requested
    if direction == -1:
      boundary = neg(self.text.focus.present())
      self.text.move_left(conjunction(enabled, mode, neg(boundary)))
      requested = self.pattern.move_right(conjunction(enabled, disjunction(neg(mode), boundary)))
      self.text_mode = conjunction(mode, neg(conjunction(enabled, mode, boundary)))
      return self.pattern.queue.name, requested
    raise ValueError("unit direction required")

  def copy_from(self, other, enabled):
    self.pattern.copy_from(other.pattern, enabled)
    self.text.copy_from(other.text, enabled)
    self.text_mode = choose(enabled, other.text_mode, self.text_mode)

  def finalize(self):
    self.circuit.put(self.mode_key, Value.select(self.text_mode, Value.constant(True), Value.constant(False)))


class OrientedHead:
  """A frozen forward or backward view, preserved by head copies."""
  def __init__(self, bank, name):
    self.circuit, self.name = bank.circuit, name
    self.cursor = bank.heads[name]
    self.mode_key = name + ".reverse"
    self.reverse = self.circuit.get(PREVIOUS, self.mode_key, (False, True), False).eq(True)

  def start(self, snapshot, reverse, enabled):
    self.cursor.copy_from(snapshot, enabled)
    self.reverse = choose(enabled, TRUE if reverse else FALSE, self.reverse)

  def read(self, enabled=TRUE):
    forward = self.cursor.peek_right(conjunction(enabled, neg(self.reverse)))
    target = Ref.select(self.reverse, self.cursor.focus, forward)
    value = self.circuit.get(target, "input", tuple("ab"), "a")
    return Value.select(target.present(), value, Value.constant(None))

  def move(self, direction, enabled):
    if direction not in (-1, 1):
      raise ValueError("unit oriented movement required")
    right = conjunction(enabled, neg(self.reverse) if direction == 1 else self.reverse)
    left = conjunction(enabled, self.reverse if direction == 1 else neg(self.reverse))
    requested = self.cursor.move_right(right)
    self.cursor.move_left(left)
    return self.cursor.queue.name, requested

  def copy_from(self, other, enabled):
    self.cursor.copy_from(other.cursor, enabled)
    self.reverse = choose(enabled, other.reverse, self.reverse)

  def finalize(self):
    self.circuit.put(self.mode_key, Value.select(self.reverse, Value.constant(True), Value.constant(False)))


class MirrorHead:
  """A frozen view of u # reverse(u), without building that word."""
  def __init__(self, bank, name):
    self.circuit, self.name = bank.circuit, name
    self.forward = bank.heads[name + ".forward"]
    self.reverse = bank.heads[name + ".reverse"]
    self.phase_key, self.nonempty_key = name + ".view_phase", name + ".nonempty"
    self.phase = self.circuit.get(PREVIOUS, self.phase_key, ("forward", "separator", "reverse", "end"), "separator")
    self.nonempty = self.circuit.get(PREVIOUS, self.nonempty_key, (False, True), False).eq(True)

  def start(self, begin, end, at_end, enabled):
    self.forward.copy_from(end if at_end else begin, enabled)
    self.reverse.copy_from(begin if at_end else end, enabled)
    self.nonempty = choose(enabled, end.focus.present(), self.nonempty)
    phase = Value.constant("end") if at_end else Value.select(end.focus.present(),
               Value.constant("forward"), Value.constant("separator"))
    self.phase = Value.select(enabled, phase, self.phase)

  def read(self, enabled=TRUE):
    front = self.forward.peek_right(conjunction(enabled, self.phase.eq("forward")))
    target = Ref.select(self.phase.eq("forward"), front, self.reverse.focus)
    value = self.circuit.get(target, "input", tuple("ab"), "a")
    data = Value.select(target.present(), value, Value.constant(None))
    return Value.select(self.phase.eq("separator"), Value.constant("#"),
                         Value.select(self.phase.eq("end"), Value.constant(None), data))

  def move(self, direction, enabled):
    phase = self.phase
    forward = conjunction(enabled, phase.eq("forward"))
    separator = conjunction(enabled, phase.eq("separator"))
    reverse = conjunction(enabled, phase.eq("reverse"))
    end = conjunction(enabled, phase.eq("end"))
    if direction == 1:
      self.circuit.require(FALSE, end)
      requested = self.forward.move_right(forward)
      self.reverse.move_left(reverse)
      self.phase = Value.select(conjunction(forward, neg(self.forward.can_right())),
                                Value.constant("separator"), self.phase)
      self.phase = Value.select(separator, Value.select(self.nonempty,
                                Value.constant("reverse"), Value.constant("end")), self.phase)
      self.phase = Value.select(conjunction(reverse, neg(self.reverse.focus.present())),
                                Value.constant("end"), self.phase)
      return self.forward.queue.name, requested
    if direction == -1:
      self.circuit.require(self.nonempty, separator)
      at_first_reverse = neg(self.reverse.can_right())
      self.forward.move_left(disjunction(forward, separator))
      requested = self.reverse.move_right(disjunction(conjunction(reverse, neg(at_first_reverse)),
                                                      conjunction(end, self.nonempty)))
      self.phase = Value.select(separator, Value.constant("forward"), self.phase)
      self.phase = Value.select(conjunction(reverse, at_first_reverse), Value.constant("separator"), self.phase)
      self.phase = Value.select(end, Value.select(self.nonempty,
                                Value.constant("reverse"), Value.constant("separator")), self.phase)
      return self.reverse.queue.name, requested
    raise ValueError("unit direction required")

  def copy_from(self, other, enabled):
    self.forward.copy_from(other.forward, enabled)
    self.reverse.copy_from(other.reverse, enabled)
    self.phase = Value.select(enabled, other.phase, self.phase)
    self.nonempty = choose(enabled, other.nonempty, self.nonempty)

  def finalize(self):
    self.circuit.put(self.phase_key, self.phase)
    self.circuit.put(self.nonempty_key, Value.select(self.nonempty, Value.constant(True), Value.constant(False)))
