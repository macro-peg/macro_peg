"""Two dyadic PAL stages in one transition per original binary input.

GS service is a fixed finite Boolean circuit, with block-window cursors and
counter origins. Historical clocks allocate one cell per actual character;
result packets store the bounded flag emissions of that same transition.
"""
from copy import copy

from gs_batch_clock import DEFAULT_BATCH
from scaffold_circuit import (Circuit, Value, Ref, PREVIOUS, EMPTY, TRUE, FALSE,
                              conjunction as AND, disjunction as OR, neg, choose)
from scaffold_circuit_structs import Stack, StackPool
from scaffold_flag_packets import FlagStack
from scaffold_window_workers import matcher, flags


class WindowStage:
  def __init__(self, circuit, index, history_pool, worker):
    self.circuit, self.index, self.worker = circuit, index, worker
    name = f"stage{index}"
    self.half = Stack(history_pool, name + ".half", "cells")
    self.clock = Stack(history_pool, name + ".clock", "cells")
    self.alive_key, self.interval_key = name + ".alive", name + ".interval"
    self.alive = circuit.get(PREVIOUS, self.alive_key, (False, True), False).eq(True)
    self.interval = circuit.get(PREVIOUS, self.interval_key, tuple(range(7)), 0)
    self.pending_key, self.batch_key = name + ".pending", name + ".batch"
    self.pending = circuit.get(PREVIOUS, self.pending_key, (False, True), False).eq(True)
    self.batch = circuit.get(PREVIOUS, self.batch_key, (0, 1, 2, 3), 0)
    self.results = [FlagStack(worker.flag_pool, name + f".result{i}") for i in range(4)]
    self.birth, self.release, self.middle = FALSE, FALSE, FALSE

  def answering(self):
    return AND(self.alive, OR(*(self.interval.eq(i) for i in range(2, 6))))

  def advance(self, birth, half):
    self.clock.drop(self.alive)
    boundary = AND(self.alive, self.clock.empty())
    self.interval = Value.select(boundary, self.interval.map(lambda n: min(n + 1, 6)), self.interval)
    self.clock.copy_from(self.half, boundary)
    release = AND(boundary, OR(*(self.interval.eq(i) for i in range(1, 5))))
    self.circuit.require(neg(self.pending), release)
    self.batch = Value.select(release, self.interval.map(lambda n: max(0, min(3, n - 1))).recode((0, 1, 2, 3)), self.batch)
    self.alive = AND(self.alive, neg(AND(boundary, self.interval.eq(6))))
    self.half.copy_from(half, birth)
    self.clock.copy_from(half, birth)
    self.interval = Value.select(birth, Value.constant(0), self.interval)
    self.alive = OR(self.alive, birth)
    self.birth, self.release = birth, AND(release, neg(birth))
    self.pending = AND(self.pending, neg(birth))
    for result in self.results: result.clear(birth)

  def consume(self):
    active = self.answering()
    selected = copy(self.results[0])
    selected.root, selected.index = EMPTY, self.worker.flag_pool.index(-1)
    for index, result in enumerate(self.results):
      selected.copy_from(result, self.interval.eq(index + 2))
    self.middle = AND(active, selected.pop(active))
    for index, result in enumerate(self.results):
      result.copy_from(selected, AND(active, self.interval.eq(index + 2)))

  def capture(self):
    self.pending = OR(self.pending, self.release)
    done = AND(self.pending, self.worker.mode.eq("done"))
    for index, result in enumerate(self.results):
      result.copy_from(self.worker.flags, AND(done, self.batch.eq(index)))
    self.pending = AND(self.pending, neg(done))

  def finalize(self):
    self.half.finalize()
    self.clock.finalize()
    for result in self.results: result.finalize()
    for key, value in ((self.interval_key, self.interval), (self.batch_key, self.batch)):
      self.circuit.put(key, value)
    for key, value in ((self.alive_key, self.alive), (self.pending_key, self.pending)):
      self.circuit.put(key, Value.select(value, Value.constant(True), Value.constant(False)))


class WindowPAL:
  def __init__(self, circuit, rates=DEFAULT_BATCH):
    if rates.k != DEFAULT_BATCH.k or rates.matching < DEFAULT_BATCH.matching or rates.flags < DEFAULT_BATCH.flags:
      raise ValueError("the complete PAL source requires the derived k=8 service bounds")
    self.circuit, self.rates = circuit, rates
    self.matchers = [matcher(circuit, f"match{i}", rates.matching) for i in range(2)]
    self.flags = [flags(circuit, f"flag{i}", rates.flags) for i in range(2)]
    history_pool = StackPool(circuit, {"cells": 1})
    self.history = Stack(history_pool, "pal.history", "cells")
    self.power = Stack(history_pool, "pal.power", "cells")
    self.next_birth = Stack(history_pool, "pal.next_birth", "cells")
    self.stages = [WindowStage(circuit, i, history_pool, self.flags[i]) for i in range(2)]
    self.power_ready = circuit.get(PREVIOUS, "pal.power_ready", (False, True), False).eq(True)
    self.slot = circuit.get(PREVIOUS, "pal.slot", (0, 1), 0)
    self.small = circuit.get(PREVIOUS, "pal.small", (0, 1, 2, 3, 4), 0)
    self.first = circuit.get(PREVIOUS, "pal.first", (False, True), False)
    self.output = TRUE

  def tick(self):
    c, current = self.circuit, self.circuit.input().eq("b")
    self.first = Value.select(self.small.eq(0), Value.select(current, Value.constant(True), Value.constant(False)), self.first)
    self.small = self.small.map(lambda n: min(4, n + 1))
    self.history.push()
    first = neg(self.power_ready)
    self.next_birth.drop(self.power_ready)
    birth = AND(self.power_ready, self.next_birth.empty())
    for index, stage in enumerate(self.stages):
      stage.advance(AND(birth, self.slot.eq(index)), self.power)
    self.power.copy_from(self.history, OR(first, birth))
    self.next_birth.copy_from(self.history, OR(first, birth))
    self.power_ready = TRUE
    self.slot = Value.select(birth, self.slot.cycle(), self.slot)

    for stage, matching, flagging in zip(self.stages, self.matchers, self.flags):
      matching.arrive()
      flagging.arrive()
      flagging.reset_flags(stage.birth)
      matching.start(stage.birth)
      flagging.start(stage.release)
      flagging.mark(stage.release)
      stage.consume()
      matching.service()
      flagging.service()
      stage.capture()

    active = [stage.answering() for stage in self.stages]
    c.require(AND(OR(*active), neg(AND(*active))), self.small.eq(4))
    ordinary = OR(*(AND(enabled, stage.middle, matching.output)
                    for enabled, stage, matching in zip(active, self.stages, self.matchers)))
    small = OR(self.small.eq(1), AND(OR(self.small.eq(2), self.small.eq(3)),
                                  choose(current, self.first.eq(True), self.first.eq(False))))
    self.output = choose(self.small.eq(4), ordinary, small)

  def finalize(self):
    for matching, flagging, stage in zip(self.matchers, self.flags, self.stages):
      matching.finalize()
      flagging.finalize()
      stage.finalize()
    for stack in (self.history, self.power, self.next_birth): stack.finalize()
    for key, value in (("pal.slot", self.slot), ("pal.small", self.small), ("pal.first", self.first)):
      self.circuit.put(key, value)
    self.circuit.put("pal.power_ready", Value.select(self.power_ready, Value.constant(True), Value.constant(False)))


def build(rates=DEFAULT_BATCH):
  circuit = Circuit("ab")
  controller = WindowPAL(circuit, rates)
  controller.tick()
  controller.finalize()
  return circuit, controller, circuit.machine(controller.output, initial_accepting=True)
