"""Finite bursts of signed counter-register operations without heap reads.

Each evolving register retains an old quotient origin and orientation, plus
an extended signed low word. The declared movement radius is less than B/2;
therefore a nonzero old quotient determines the sign throughout the round.
Only finalization multiplexes quotient pointers and allocates their carry.
"""
from dataclasses import dataclass

from scaffold_circuit import (Value, Ref, EMPTY, TRUE, FALSE, choose,
                              conjunction as AND, disjunction as OR, neg)
from scaffold_window_counter import WindowCounters, Bits


def select_value(cases, domain):
  values = [(guard, value.recode(domain)) for guard, value in cases if guard != FALSE]
  return Value.encoded(domain,
    (OR(*(AND(guard, value.bits[bit]) for guard, value in values))
      for bit in range((len(domain) - 1).bit_length())),
    OR(*(AND(guard, value.valid) for guard, value in values)))


@dataclass
class Register:
  origin: Value
  reverse: tuple
  digits: Bits
  radius: int = 0


class WindowRegisters:
  def __init__(self, circuit, names, radius):
    self.circuit, self.names, self.radius = circuit, tuple(names), radius
    self.bank = WindowCounters(circuit, names, radius)
    self.saved = {name: counter.clone() for name, counter in self.bank.counters.items()}
    self.width = self.bank.width + 1
    self.old = {name: (counter.quotient.zero(), counter.quotient.negative(),
                       counter.quotient.positive()) for name, counter in self.saved.items()}
    self.registers = {name: Register(Value.constant(name).recode(self.names), FALSE,
                                    counter.digits) for name, counter in self.saved.items()}

  def select(self, which, registers=None):
    registers = self.registers if registers is None else registers
    cases = [(which.eq(name), registers[name]) for name in which.domain
             if name in registers and which.eq(name) != FALSE]
    return Register(select_value([(guard, source.origin) for guard, source in cases], self.names),
      OR(*(AND(guard, source.reverse) for guard, source in cases)),
      Bits(tuple(OR(*(AND(guard, source.digits.bits[bit]) for guard, source in cases))
                 for bit in range(self.width))),
      max((source.radius for _, source in cases), default=0))

  def assign(self, target, source, enabled=TRUE, reverse=FALSE):
    if enabled == FALSE: return
    prior = self.registers[target]
    digits = Bits.select(reverse, source.digits.negated(), source.digits)
    self.registers[target] = Register(
      Value.select(enabled, source.origin, prior.origin),
      choose(enabled, choose(reverse, neg(source.reverse), source.reverse), prior.reverse),
      Bits.select(enabled, digits, prior.digits),
      source.radius if enabled == TRUE else max(source.radius, prior.radius))

  def reset(self, target, enabled=TRUE):
    prior = self.registers[target]
    # Invalid origin denotes the exact constant zero, without a quotient.
    zero = Register(Value.encoded(self.names, (FALSE,) * len(prior.origin.bits), FALSE),
                    FALSE, Bits.constant(0, self.width))
    self.assign(target, zero, enabled)

  def add(self, target, amount, enabled=TRUE):
    if enabled == FALSE or amount == 0: return
    prior = self.registers[target]
    if type(amount) is not int or prior.radius + abs(amount) > self.radius:
      raise ValueError("register lineage exceeds its proved finite radius")
    self.registers[target] = Register(prior.origin, prior.reverse,
      Bits.select(enabled, prior.digits.add(amount), prior.digits), prior.radius + abs(amount))

  def add_selected(self, target, amount, enabled=TRUE):
    prior = self.registers[target]
    bound = max(map(abs, amount.domain))
    if prior.radius + bound > self.radius:
      raise ValueError("register lineage exceeds its proved finite radius")
    digits = prior.digits
    for delta in amount.domain:
      if delta:
        digits = Bits.select(AND(enabled, amount.eq(delta)), prior.digits.add(delta), digits)
    self.registers[target] = Register(prior.origin, prior.reverse, digits, prior.radius + bound)

  def compare_zero(self, register):
    near, below, above = neg(register.origin.valid), FALSE, FALSE
    for name, (zero, negative, positive) in self.old.items():
      selected = register.origin.eq(name)
      near = OR(near, AND(selected, zero))
      below = OR(below, AND(selected, negative))
      above = OR(above, AND(selected, positive))
    return AND(near, register.digits.zero()), OR(
      choose(register.reverse, above, below), AND(near, register.digits.negative()))

  def finalize(self):
    for name, target in self.bank.counters.items():
      register = self.registers[name]
      for side in ("pos", "neg"):
        stack = getattr(target.quotient, side)
        top, cases = EMPTY, []
        for origin, source in self.saved.items():
          for reverse in (False, True):
            guard = AND(register.origin.eq(origin), register.reverse if reverse else neg(register.reverse))
            selected = getattr(source.quotient, ("neg" if side == "pos" else "pos") if reverse else side)
            top = Ref.select(guard, selected.top, top)
            cases.append((guard, selected.tag))
        stack.top = top
        stack.tag = select_value(cases, stack.tag.domain)
      target.digits, target.radius = register.digits, register.radius
    self.bank.finalize()
