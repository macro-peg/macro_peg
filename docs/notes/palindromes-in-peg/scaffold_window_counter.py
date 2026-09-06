"""Counter updates inside a fixed round without intermediate heap cells.

A counter is B*Q+r, with a signed unary quotient Q and -B/2 <= r < B/2. During
the round only r changes, in a finite signed bit vector; copies also copy Q.
If every lineage changes by less than B/2, normalization changes Q by at most
one. Thus each counter needs one final allocation slot, independently of the
number of intermediate instructions. This is not a complete round compiler.
"""
from dataclasses import dataclass
from copy import copy

from scaffold_circuit import (Value, Ref, PREVIOUS, TRUE, FALSE, choose,
                              conjunction as AND, disjunction as OR, neg)
from scaffold_circuit_structs import Counter, StackPool


@dataclass(frozen=True)
class Bits:
  bits: tuple

  @classmethod
  def constant(cls, value, width):
    return cls(tuple(TRUE if value & (1 << bit) else FALSE for bit in range(width)))

  @classmethod
  def load(cls, circuit, key, width):
    return cls(tuple(circuit.get(PREVIOUS, f"{key}.{bit}", (False, True), False).eq(True)
                     for bit in range(width)))

  def store(self, circuit, key):
    for bit, expression in enumerate(self.bits):
      circuit.put(f"{key}.{bit}",
        Value.select(expression, Value.constant(True), Value.constant(False)),
        (False, True), False)

  def zero(self):
    return AND(*(neg(bit) for bit in self.bits))

  def negative(self):
    return self.bits[-1]

  def negated(self):
    return Bits(tuple(neg(bit) for bit in self.bits)).add(1)

  def plus(self, other):
    if len(self.bits) != len(other.bits):
      raise ValueError("finite words need a common width")
    carry, result = FALSE, []
    for a, b in zip(self.bits, other.bits):
      unequal = choose(a, neg(b), b)
      result.append(choose(carry, neg(unequal), unequal))
      carry = OR(AND(a, b), AND(carry, unequal))
    return Bits(tuple(result))

  def equal(self, other):
    if len(self.bits) != len(other.bits):
      raise ValueError("finite words need a common width")
    return AND(*(choose(a, b, neg(b)) for a, b in zip(self.bits, other.bits)))

  def unsigned_less(self, other):
    if len(self.bits) != len(other.bits):
      raise ValueError("finite words need a common width")
    less = FALSE
    for a, b in zip(self.bits, other.bits):
      less = OR(AND(neg(a), b), AND(choose(a, b, neg(b)), less))
    return less

  def add(self, amount):
    """A fixed signed addend, modulo the finite bit-vector width."""
    carry, result = FALSE, []
    for index, bit in enumerate(self.bits):
      if amount & (1 << index):
        result.append(choose(carry, bit, neg(bit)))
        carry = OR(bit, carry)
      else:
        result.append(choose(carry, neg(bit), bit))
        carry = AND(bit, carry)
    return Bits(tuple(result))

  @staticmethod
  def select(enabled, yes, no):
    if len(yes.bits) != len(no.bits):
      raise ValueError("finite words need a common width")
    return Bits(tuple(choose(enabled, a, b) for a, b in zip(yes.bits, no.bits)))


class WindowCounter:
  def __init__(self, bank, name, slot):
    self.bank, self.circuit, self.name, self.slot = bank, bank.circuit, name, slot
    self.quotient = Counter(bank.pool, name + ".quotient", "cells")
    low = Bits.load(self.circuit, name + ".low", bank.width)
    self.digits = Bits((*low.bits, low.negative()))
    # A construction-time bound, not a runtime value or sampled maximum.
    self.radius = 0
    self.finished = False

  def _carry(self):
    high, sign = self.digits.bits[-2:]
    up, down = AND(high, neg(sign)), AND(neg(high), sign)
    return up, down, neg(OR(up, down))

  def _quotient_state(self):
    positive, negative = self.quotient.positive(), self.quotient.negative()
    zero = AND(neg(positive), neg(negative))
    single = []
    for stack, has in ((self.quotient.pos, positive), (self.quotient.neg, negative)):
      below = stack.pool.pointer(stack.top, stack.tag, "below")
      single.append(AND(has, neg(below.present())))
    up, down, same = self._carry()
    normalized_zero = OR(AND(same, zero), AND(up, single[1]), AND(down, single[0]))
    normalized_negative = OR(AND(negative, neg(AND(up, single[1]))), AND(down, zero))
    normalized_positive = OR(AND(positive, neg(AND(down, single[0]))), AND(up, zero))
    return normalized_zero, normalized_negative, normalized_positive

  def zero(self):
    quotient_zero, _, _ = self._quotient_state()
    return AND(quotient_zero, Bits(self.digits.bits[:self.bank.width]).zero())

  def negative(self):
    zero, negative, _ = self._quotient_state()
    return OR(negative, AND(zero, self.digits.bits[self.bank.width - 1]))

  def positive(self):
    zero, _, positive = self._quotient_state()
    low = Bits(self.digits.bits[:self.bank.width])
    return OR(positive, AND(zero, neg(low.negative()), neg(low.zero())))

  def clone(self):
    result = copy(self)
    result.quotient = copy(self.quotient)
    result.quotient.pos, result.quotient.neg = copy(self.quotient.pos), copy(self.quotient.neg)
    return result

  def add_word(self, delta, radius, enabled=TRUE):
    """Add a finite signed word with a separately supplied static bound."""
    if self.finished or type(radius) is not int or radius < 0:
      raise ValueError("a live counter and a finite nonnegative bound are required")
    if enabled == FALSE:
      return
    if self.radius + radius >= self.bank.base // 2:
      raise ValueError("counter lineage exceeds the declared finite window")
    self.digits = Bits.select(enabled, self.digits.plus(delta), self.digits)
    self.radius += radius

  def add(self, amount, enabled=TRUE):
    if self.finished:
      raise ValueError("counter round has already been finalized")
    if type(amount) is not int:
      raise ValueError("a fixed integer update is required")
    if enabled == FALSE or amount == 0:
      return
    radius = self.radius + abs(amount)
    if radius >= self.bank.base // 2:
      raise ValueError("counter lineage exceeds the declared finite window")
    self.digits = Bits.select(enabled, self.digits.add(amount), self.digits)
    self.radius = radius

  def inc(self, enabled=TRUE):
    self.add(1, enabled)

  def dec(self, enabled=TRUE):
    self.add(-1, enabled)

  def copy_from(self, other, enabled=TRUE, negate_value=False):
    if self.bank is not other.bank or self.finished:
      raise ValueError("copy requires live counters in the same window bank")
    if enabled == FALSE:
      return
    sources = (other.quotient.neg, other.quotient.pos) if negate_value else \
              (other.quotient.pos, other.quotient.neg)
    snapshots = [(stack.top, stack.tag) for stack in sources]
    for destination, (top, tag) in zip((self.quotient.pos, self.quotient.neg), snapshots):
      destination.top = Ref.select(enabled, top, destination.top)
      destination.tag = Value.select(enabled, tag, destination.tag)
    self.digits = Bits.select(enabled, other.digits.negated() if negate_value else other.digits,
                               self.digits)
    self.radius = other.radius if enabled == TRUE else max(self.radius, other.radius)

  def reset(self, enabled=TRUE):
    if self.finished:
      raise ValueError("counter round has already been finalized")
    self.quotient.reset(enabled)
    self.digits = Bits.select(enabled, Bits.constant(0, self.bank.width + 1), self.digits)
    if enabled == TRUE:
      self.radius = 0

  def finalize(self):
    if self.finished:
      raise ValueError("counter round has already been finalized")
    up, down, _ = self._carry()
    self.quotient.inc(up, slot=self.slot)
    self.quotient.dec(down, slot=self.slot)
    self.quotient.finalize()
    Bits(self.digits.bits[:self.bank.width]).store(self.circuit, self.name + ".low")
    self.finished = True


class WindowCounters:
  def __init__(self, circuit, names, radius):
    self.circuit, self.names = circuit, tuple(names)
    if not self.names or len(set(self.names)) != len(self.names):
      raise ValueError("distinct finite counter names are required")
    if type(radius) is not int or radius < 0:
      raise ValueError("a nonnegative construction-time radius is required")
    self.width = max(1, radius.bit_length() + 1)
    self.base = 1 << self.width
    self.pool = StackPool(circuit, {"cells": len(self.names)})
    self.counters = {name: WindowCounter(self, name, slot)
                     for slot, name in enumerate(self.names)}

  def finalize(self):
    for counter in self.counters.values():
      counter.finalize()


def fixture(radius=31):
  """Each actual character performs conditional arithmetic and copies."""
  from scaffold_circuit import Circuit
  circuit = Circuit("abcdefgh")
  bank = WindowCounters(circuit, ("x", "y"), radius)
  x, y = (bank.counters[name] for name in bank.names)
  char = circuit.input()
  x.add(7, char.eq("a"))
  x.add(-5, char.eq("b"))
  y.copy_from(x, char.eq("c"))
  x.copy_from(y, char.eq("d"))
  y.add(-3, char.eq("e"))
  x.reset(char.eq("f"))
  x.copy_from(x, char.eq("g"), negate_value=True)
  y.copy_from(x, char.eq("h"), negate_value=True)
  # Tests observe the unnormalized value, including both carry directions.
  for counter in (x, y):
    for name, condition in (("zero", counter.zero()), ("negative", counter.negative()),
                             ("positive", counter.positive())):
      circuit.put(counter.name + "." + name,
        Value.select(condition, Value.constant(True), Value.constant(False)),
        (False, True), name == "zero")
  answer = x.zero()
  bank.finalize()
  return circuit, bank, circuit.machine(answer, initial_accepting=True)
