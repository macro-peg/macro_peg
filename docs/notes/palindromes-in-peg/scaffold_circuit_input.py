"""Clonable readonly letter/gap input heads in the symbolic scaffold circuit."""
from scaffold_circuit import (Value, Ref, EMPTY, PREVIOUS, TRUE, FALSE,
                              choose, conjunction, disjunction, neg)
from scaffold_circuit_structs import Stack, Queue


class InputHead:
  def __init__(self, head_pool, queue_pool, counter_pool, name, alphabet="ab"):
    pools = head_pool if isinstance(head_pool, dict) else {"l": head_pool, "r": head_pool}
    self.circuit, self.name, self.alphabet = pools["l"].circuit, name, tuple(alphabet)
    self.focus_key = name + ".focus"
    self.focus = self.circuit.get_ref(PREVIOUS, self.focus_key)
    self.left_stack, self.right_stack = Stack(pools["l"], name + ".l"), Stack(pools["r"], name + ".r")
    self.incoming = Queue(queue_pool, counter_pool, name + ".in")

  def append(self, cell, enabled=TRUE):
    self.incoming.push(cell, enabled)
    self.incoming.work(enabled)

  def read(self):
    symbol = self.circuit.get(self.focus, "input", self.alphabet, self.alphabet[0])
    return Value.select(self.focus.present(), symbol, Value.constant(None))

  def can_right(self):
    return disjunction(neg(self.right_stack.empty()), neg(self.incoming.empty()))

  def peek_right(self):
    """Read the next cell at an operation boundary without moving the head."""
    saved, _ = self.right_stack.peek()
    arrived, _ = self.incoming.s["F"].peek()
    from_stack = neg(self.right_stack.empty())
    self.circuit.require(neg(self.incoming.s["F"].empty()),
                         conjunction(neg(from_stack), neg(self.incoming.empty())))
    return Ref.select(from_stack, saved, arrived)

  def reset(self, enabled=TRUE):
    self.focus = Ref.select(enabled, EMPTY, self.focus)
    self.left_stack.clear(enabled)
    self.right_stack.clear(enabled)
    self.incoming.clear(enabled)

  def right(self, enabled=TRUE):
    self.circuit.require(self.can_right(), enabled)
    self.left_stack.push(self.focus, enabled=enabled)
    from_stack = neg(self.right_stack.empty())
    from_queue = conjunction(enabled, neg(from_stack))
    saved, _ = self.right_stack.pop(conjunction(enabled, from_stack))
    self.incoming.work(from_queue)
    arrived = self.incoming.pop(from_queue)
    self.incoming.work(from_queue)
    self.focus = Ref.select(enabled, Ref.select(from_stack, saved, arrived), self.focus)

  def left(self, enabled=TRUE):
    self.circuit.require(neg(self.left_stack.empty()), enabled)
    self.right_stack.push(self.focus, enabled=enabled)
    saved, _ = self.left_stack.pop(enabled)
    self.focus = Ref.select(enabled, saved, self.focus)

  def copy_from(self, other, enabled=TRUE):
    self.focus = Ref.select(enabled, other.focus, self.focus)
    self.left_stack.copy_from(other.left_stack, enabled)
    self.right_stack.copy_from(other.right_stack, enabled)
    self.incoming.copy_from(other.incoming, enabled)

  def finalize(self, maintain=True):
    if maintain:
      self.incoming.work()
    self.left_stack.finalize(); self.right_stack.finalize()
    self.incoming.finalize()
    self.circuit.put_ref(self.focus_key, self.focus)


class PlaceHead:
  def __init__(self, head_pool, queue_pool, counter_pool, name, alphabet="ab"):
    self.head = InputHead(head_pool, queue_pool, counter_pool, name, alphabet)
    self.circuit, self.name = self.head.circuit, name
    self.gap_key = name + ".gap"
    self.gap = self.circuit.get(PREVIOUS, self.gap_key, (False, True), True).eq(True)

  def append(self, cell, enabled=TRUE):
    self.head.append(cell, enabled)

  def read(self):
    value = self.head.read()
    return Value.select(value.eq(None), Value.constant(None),
                        Value.select(self.gap, Value.constant("s"), value))

  def can_right(self):
    return disjunction(neg(self.gap), self.head.can_right())

  def right(self, enabled=TRUE):
    self.head.right(conjunction(enabled, self.gap))
    self.gap = choose(enabled, neg(self.gap), self.gap)

  def left(self, enabled=TRUE):
    self.circuit.require(neg(self.head.read().eq(None)), enabled)
    self.head.left(conjunction(enabled, neg(self.gap)))
    self.gap = choose(enabled, neg(self.gap), self.gap)

  def is_first(self):
    before, _ = self.head.left_stack.peek()
    return conjunction(neg(self.gap), neg(self.head.left_stack.empty()), neg(before.present()))

  def copy_from(self, other, enabled=TRUE):
    self.head.copy_from(other.head, enabled)
    self.gap = choose(enabled, other.gap, self.gap)

  def finalize(self):
    self.head.finalize()
    self.circuit.put(self.gap_key, Value.select(self.gap, Value.constant(True), Value.constant(False)))
