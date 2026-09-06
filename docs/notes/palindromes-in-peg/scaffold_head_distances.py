"""Order/equality of finitely many heads by maintained signed differences.

This does not compare SCA pointers. The controller must apply the same moves
and copies to its actual heads. It cannot load an arbitrary historical head
and infer that head's distances to the current bank.
"""
from scaffold_circuit import TRUE, FALSE
from scaffold_circuit_structs import Counter, StackPool


class HeadDistances:
  def __init__(self, circuit, names, *, moves=None, prefix="head.distance", exclusive_moves=False):
    self.names = tuple(names)
    if len(self.names) < 2 or len(set(self.names)) != len(self.names):
      raise ValueError("at least two distinct fixed head names required")
    self.index = {name: i for i, name in enumerate(self.names)}
    self.exclusive_moves = exclusive_moves
    moves = {name: (1, 1) for name in self.names} if moves is None else dict(moves)
    if set(moves) != set(self.names) or any(
        len(counts) != 2 or any(type(n) is not int or n < 0 for n in counts)
        for counts in moves.values()):
      raise ValueError("each head needs nonnegative (left, right) construction counts")
    layout, keys = {}, {}
    for i, left in enumerate(self.names):
      for right in self.names[i + 1:]:
        key = f"{prefix}.{left}.{right}"
        keys[left, right] = key
        layout[key + ".pos"] = moves[left][1] + moves[right][0]
        layout[key + ".neg"] = moves[left][0] + moves[right][1]
    if not any(layout.values()):
      raise ValueError("the bank requires at least one declared movement")
    # With at most one moving head per physical step, its differences to
    # each other head need one cell apiece. Both signs and all possible
    # moving heads may share those H slots under disjoint instruction guards.
    self.pool = StackPool(circuit, {"cells": len(self.names)} if exclusive_moves else layout)
    self.counters = {pair: Counter(self.pool, key, "cells" if exclusive_moves else None)
                     for pair, key in keys.items()}

  def _ordered(self, left, right):
    if left not in self.index or right not in self.index:
      raise ValueError("unknown head")
    return (left, right) if self.index[left] < self.index[right] else (right, left)

  def equal(self, left, right):
    pair = self._ordered(left, right)
    return TRUE if left == right else self.counters[pair].zero()

  def less(self, left, right):
    pair = self._ordered(left, right)
    if left == right:
      return FALSE
    counter = self.counters[pair]
    return counter.negative() if pair[0] == left else counter.positive()

  def move(self, head, direction, enabled=TRUE):
    if head not in self.index or direction not in (-1, 1):
      raise ValueError("known head and unit direction required")
    for pair, counter in self.counters.items():
      if head not in pair:
        continue
      change = direction if pair[0] == head else -direction
      other = pair[1] if pair[0] == head else pair[0]
      slot = self.index[other] if self.exclusive_moves else None
      (counter.inc if change == 1 else counter.dec)(enabled, slot=slot)

  def copy(self, target, source, enabled=TRUE):
    self._ordered(target, source)
    if target == source:
      return
    for pair, counter in self.counters.items():
      if target not in pair:
        continue
      left, right = (source if name == target else name for name in pair)
      if left == right:
        counter.reset(enabled)
        continue
      source_pair = self._ordered(left, right)
      original = self.counters[source_pair]
      positive, negative = (original.pos, original.neg) if source_pair[0] == left else \
                           (original.neg, original.pos)
      # Source pairs exclude target, so these updates cannot overwrite a
      # distance subsequently needed by this same head-copy operation.
      counter.pos.copy_from(positive, enabled)
      counter.neg.copy_from(negative, enabled)

  def coincide(self, enabled=TRUE):
    """Reset after the associated controller has placed all heads together."""
    for counter in self.counters.values():
      counter.reset(enabled)

  def finalize(self):
    for counter in self.counters.values():
      counter.finalize()
