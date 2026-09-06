"""Head order over a bounded round, with no intermediate distance cells.

An evolving head is (old head origin, finite signed displacement). The old
pair differences are saved only once per input node. Comparisons multiplex
their finite near-zero information; large quotients have a stable sign.
At the end each pair is normalized once through WindowCounters.
"""
from dataclasses import dataclass
from itertools import combinations

from scaffold_circuit import Value, TRUE, FALSE, conjunction as AND, disjunction as OR, neg
from scaffold_window_counter import WindowCounters, Bits


@dataclass
class Position:
  origin: Value
  offset: Bits
  radius: int = 0


class WindowPositions:
  def __init__(self, circuit, names, radius, prefix="positions"):
    self.circuit, self.names, self.radius = circuit, tuple(names), radius
    if len(self.names) < 2 or len(set(self.names)) != len(self.names):
      raise ValueError("at least two distinct head names are required")
    if type(radius) is not int or radius < 0:
      raise ValueError("a nonnegative finite movement bound is required")
    self.pairs = tuple(combinations(self.names, 2))
    keys = tuple(f"{prefix}.{i}" for i in range(len(self.pairs)))
    self.bank = WindowCounters(circuit, keys, 2 * radius)
    self.counters = dict(zip(self.pairs, self.bank.counters.values()))
    self.saved = {pair: counter.clone() for pair, counter in self.counters.items()}
    self.width = self.bank.width + 1
    self.heads = {name: Position(Value.constant(name).recode(self.names), Bits.constant(0, self.width))
                  for name in self.names}
    self.old = {pair: (counter.quotient.zero(), counter.quotient.negative(),
                       counter.quotient.positive(), counter.digits)
                for pair, counter in self.saved.items()}

  def move(self, head, amount, enabled=TRUE):
    if type(amount) is not int:
      raise ValueError("a fixed signed head displacement is required")
    if enabled == FALSE or amount == 0:
      return
    target = self.heads[head]
    if target.radius + abs(amount) > self.radius:
      raise ValueError("head lineage exceeds the declared movement bound")
    target.offset = Bits.select(enabled, target.offset.add(amount), target.offset)
    target.radius += abs(amount)

  def copy(self, target, source, enabled=TRUE):
    self.copy_position(target, self.heads[source], enabled)

  def copy_position(self, target, source, enabled=TRUE):
    a, b = self.heads[target], source
    a.origin = Value.select(enabled, b.origin, a.origin)
    a.offset = Bits.select(enabled, b.offset, a.offset)
    a.radius = b.radius if enabled == TRUE else max(a.radius, b.radius)

  def select_position(self, which):
    cases = [(which.eq(name), head) for name, head in self.heads.items() if which.eq(name) != FALSE]
    origin = Value.encoded(self.names,
      (OR(*(AND(guard, head.origin.bits[bit]) for guard, head in cases))
       for bit in range((len(self.names) - 1).bit_length())),
      OR(*(guard for guard, _ in cases)))
    offset = Bits(tuple(OR(*(AND(guard, head.offset.bits[bit]) for guard, head in cases))
                        for bit in range(self.width)))
    return Position(origin, offset, max((head.radius for _, head in cases), default=0))

  def origin_cases(self, left, right):
    yield from self.position_cases(self.heads[left], self.heads[right])

  def position_cases(self, a, b):
    for i, j in self.pairs:
      yield (i, j), AND(a.origin.eq(i), b.origin.eq(j)), AND(a.origin.eq(j), b.origin.eq(i))

  def compare(self, left, right):
    if left == right:
      return TRUE, FALSE
    return self.compare_positions(self.heads[left], self.heads[right])

  def compare_positions(self, a, b):
    near = OR(*(AND(a.origin.eq(name), b.origin.eq(name)) for name in self.names))
    negative, digits = FALSE, Bits.constant(0, self.width)
    for pair, forward, backward in self.position_cases(a, b):
      zero, below, above, value = self.old[pair]
      near = OR(near, AND(OR(forward, backward), zero))
      negative = OR(negative, AND(forward, below), AND(backward, above))
      digits = Bits.select(forward, value, digits)
      digits = Bits.select(backward, value.negated(), digits)
    difference = digits.plus(a.offset).plus(b.offset.negated())
    return AND(near, difference.zero()), OR(negative, AND(near, difference.negative()))

  def equal(self, left, right):
    return self.compare(left, right)[0]

  def less(self, left, right):
    return self.compare(left, right)[1]

  def finalize(self):
    for (left, right), target in self.counters.items():
      target.reset()
      for pair, forward, backward in self.origin_cases(left, right):
        target.copy_from(self.saved[pair], forward)
        target.copy_from(self.saved[pair], backward, negate_value=True)
      a, b = self.heads[left], self.heads[right]
      target.add_word(a.offset.plus(b.offset.negated()), a.radius + b.radius)
    self.bank.finalize()
