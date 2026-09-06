"""Compile only live head differences, sharing finite counter registers."""
from gs_head_liveness import analyze
from scaffold_circuit import (Value, Ref, TRUE, FALSE, disjunction)
from scaffold_circuit_structs import Counter, StackPool


class LiveDistances:
  def __init__(self, circuit, program, names, prefix="distance", **analysis_options):
    self.circuit, self.program = circuit, program
    self.analysis = analyze(program, names, **analysis_options)
    self.names = self.analysis.names
    self.pool = StackPool(circuit, {"cells": self.analysis.registers + 1})
    self.registers = [Counter(self.pool, f"{prefix}.r{i}", "cells")
                      for i in range(self.analysis.registers)]
    self.counters = {pair: self.registers[color] for pair, color in self.analysis.colors.items()}
    self.length = Counter(self.pool, prefix + ".load_length", "cells")

  def equal(self, left, right):
    pair, _ = self.analysis.canonical(left, right)
    return TRUE if pair is None else self.counters[pair].zero()

  def less(self, left, right):
    pair, sign = self.analysis.canonical(left, right)
    if pair is None:
      return FALSE
    counter = self.counters[pair]
    return counter.negative() if sign == 1 else counter.positive()

  def load_one(self, enabled):
    self.length.inc(enabled, slot=self.analysis.registers)

  def initialize(self, values, enabled):
    if set(values) != self.analysis.before[self.program.start]:
      raise ValueError("every live entry difference needs an explicit initialization")
    for pair, value in values.items():
      target = self.counters[pair]
      if value is None:
        target.reset(enabled)
      else:
        source, sign = value
        self._assign(target, source.pos, source.neg, sign, enabled)

  @staticmethod
  def _assign(target, positive, negative, sign, enabled):
    if sign == -1:
      positive, negative = negative, positive
    target.pos.copy_from(positive, enabled)
    target.neg.copy_from(negative, enabled)

  def execute(self, events):
    if len(events) != len(self.program.code):
      raise ValueError("one instruction guard per finite state required")
    increments = [[] for _ in self.registers]
    decrements = [[] for _ in self.registers]
    resets, copies = {}, {}
    for state, ((event, _), enabled) in enumerate(zip(self.program.code, events)):
      op = event[0]
      if op == "move":
        if len(event[1]) != 1 or abs(event[1][0][1]) != 1:
          raise ValueError("unit movement required")
        head, amount = event[1][0]
        for pair in self.analysis.after[state]:
          if head in pair:
            change = amount if pair[0] == head else -amount
            (increments if change == 1 else decrements)[self.analysis.colors[pair]].append(enabled)
      elif op == "copy":
        _, target, source = event
        for pair in self.analysis.after[state]:
          if target not in pair:
            continue
          left, right = (source if name == target else name for name in pair)
          original, sign = self.analysis.canonical(left, right)
          destination = self.analysis.colors[pair]
          if original is None:
            resets.setdefault(destination, []).append(enabled)
          else:
            origin = self.analysis.colors[original]
            if destination != origin or sign != 1:
              copies.setdefault((destination, origin, sign), []).append(enabled)
    # Copy sources must be simultaneous pre-instruction values. In particular,
    # two differently named live pairs may exchange their physical registers.
    snapshots = [Counter(self.pool, counter.pos.name.removesuffix(".pos"), "cells")
                 for counter in self.registers]
    for index, counter in enumerate(self.registers):
      counter.inc(disjunction(*increments[index]), slot=index)
      counter.dec(disjunction(*decrements[index]), slot=index)
    for index, conditions in resets.items():
      self.registers[index].reset(disjunction(*conditions))
    for (destination, origin, sign), conditions in copies.items():
      source = snapshots[origin]
      self._assign(self.registers[destination], source.pos, source.neg, sign, disjunction(*conditions))

  def finalize(self):
    for counter in self.registers:
      counter.finalize()
    self.length.finalize()
