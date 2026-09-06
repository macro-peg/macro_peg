"""Many flag pushes per round, stored in one immutable packet per input node.

Push slots are finite construction indices. Each records its bit and the
preceding valid slot, so skipped pushes need no search on pop. The first
valid slot links to the preceding packet. Roots and within-packet indices
are copied together. No intermediate virtual scaffold nodes are allocated.
"""
from scaffold_circuit import (Circuit, Value, Ref, PREVIOUS, NEW, EMPTY, TRUE, FALSE,
                              conjunction as AND, disjunction as OR, neg)
from scaffold_window_counter import Bits


class FlagPackets:
  def __init__(self, circuit, capacity, prefix="packets"):
    if type(capacity) is not int or capacity < 1:
      raise ValueError("a positive finite packet capacity is required")
    self.circuit, self.capacity, self.prefix = circuit, capacity, prefix
    self.indices = (-1, *range(capacity))
    self.width = capacity.bit_length()
    self.back_key, self.index_key = prefix + ".previous", prefix + ".previous_index"
    self.writer_created = False

  def index(self, slot):
    if not -1 <= slot < self.capacity:
      raise ValueError("packet slot outside the finite layout")
    return Value.encoded(self.indices, Bits.constant(slot + 1, self.width).bits)

  def equal(self, index, slot):
    return Bits(tuple(index.bits)).equal(Bits.constant(slot + 1, self.width))

  def fields(self, slot):
    return f"{self.prefix}.{slot}.bit", f"{self.prefix}.{slot}.previous_index"

  def read(self, root, index):
    bit, previous = FALSE, [FALSE] * self.width
    for slot in range(self.capacity):
      enabled = self.equal(index, slot)
      if enabled == FALSE:
        continue
      bit_key, previous_key = self.fields(slot)
      value = self.circuit.get(root, bit_key, (False, True), False).eq(True)
      saved = self.circuit.get(root, previous_key, self.indices, -1)
      bit = OR(bit, AND(enabled, value))
      previous = [OR(result, AND(enabled, field)) for result, field in zip(previous, saved.bits)]
    return bit, Value.encoded(self.indices, previous)


class FlagStack:
  def __init__(self, pool, name):
    self.pool, self.circuit, self.name = pool, pool.circuit, name
    self.root_key, self.index_key = name + ".root", name + ".index"
    self.root = self.circuit.get_ref(PREVIOUS, self.root_key)
    self.index = self.circuit.get(PREVIOUS, self.index_key, pool.indices, -1)

  def empty(self):
    return neg(self.root.present())

  def clear(self, enabled=TRUE):
    self.root = Ref.select(enabled, EMPTY, self.root)
    self.index = Value.select(enabled, self.pool.index(-1), self.index)

  def copy_from(self, other, enabled=TRUE):
    if other.pool is not self.pool:
      raise ValueError("flag stack copies require a common packet pool")
    self.root = Ref.select(enabled, other.root, self.root)
    self.index = Value.select(enabled, other.index, self.index)

  def pop(self, enabled=TRUE):
    self.circuit.require(neg(self.empty()), enabled)
    bit, previous = self.pool.read(self.root, self.index)
    boundary = self.pool.equal(previous, -1)
    back_root = self.circuit.get_ref(self.root, self.pool.back_key)
    back_index = self.circuit.get(self.root, self.pool.index_key, self.pool.indices, -1)
    self.root = Ref.select(AND(enabled, boundary), back_root, self.root)
    self.index = Value.select(enabled, Value.select(boundary, back_index, previous), self.index)
    return bit

  def finalize(self):
    self.circuit.put_ref(self.root_key, self.root)
    self.circuit.put(self.index_key, self.index)


class PacketWriter:
  def __init__(self, stack):
    self.stack, self.pool, self.circuit = stack, stack.pool, stack.circuit
    if self.pool.writer_created:
      raise ValueError("one writer per packet pool and input node is required")
    self.pool.writer_created = True
    self.started, self.next_slot = FALSE, 0
    # The stack may be cleared or popped before constructing its next packet.
    self.circuit.put_ref(self.pool.back_key, stack.root)
    self.circuit.put(self.pool.index_key, stack.index, self.pool.indices, -1)

  def push(self, bit, enabled=TRUE):
    if self.next_slot >= self.pool.capacity:
      raise ValueError("finite flag packet capacity exhausted")
    slot = self.next_slot
    self.next_slot += 1
    bit_key, previous_key = self.pool.fields(slot)
    previous = Value.select(self.started, self.stack.index, self.pool.index(-1))
    self.circuit.put(bit_key, Value.select(bit, Value.constant(True), Value.constant(False)),
                     (False, True), False)
    self.circuit.put(previous_key, previous, self.pool.indices, -1)
    self.stack.root = Ref.select(enabled, NEW, self.stack.root)
    self.stack.index = Value.select(enabled, self.pool.index(slot), self.stack.index)
    self.started = OR(self.started, enabled)


def fixture(capacity=7):
  circuit = Circuit("ab<>cr!")
  pool = FlagPackets(circuit, capacity)
  x, y = FlagStack(pool, "x"), FlagStack(pool, "y")
  char = circuit.input()
  x.clear(char.eq("!"))
  y.copy_from(x, char.eq("c"))
  x.copy_from(y, char.eq("r"))
  a = x.pop(AND(char.eq("<"), neg(x.empty())))
  b = y.pop(AND(char.eq(">"), neg(y.empty())))
  writer = PacketWriter(x)
  for slot in range(capacity):
    enabled = OR(char.eq("a"), char.eq("b") if slot % 3 != 1 else FALSE)
    bit = char.eq("a") if slot % 2 == 0 else char.eq("b")
    writer.push(bit, enabled)
  answer = OR(AND(char.eq("<"), a), AND(char.eq(">"), b))
  x.finalize()
  y.finalize()
  return circuit, pool, (x, y), circuit.machine(answer)
