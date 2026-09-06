"""The complete two-stage PAL controller at explicit local-instruction speed.

One input round has a fixed, derived number of local transitions. The byte
on the ready transition is the next actual input; intervening byte values
are ignored. `round`-folding to one ordinary input symbol remains a separate
compiler step. This machine/file alone is NOT a grammar for unchanged PAL.
"""
from scaffold_circuit import (Circuit, Value, PREVIOUS, TRUE, FALSE,
                              conjunction, disjunction, neg, choose)
from scaffold_circuit_structs import Stack
from scaffold_gs_matcher import GSMatcher
from scaffold_gs_flags import GSFlags
from gs_local_clock import DEFAULT, DEFAULT_DUAL


PHASES = ("ready", "settle", "deliver", "broadcast", "clock", "start", "mark", "middle", "serve", "finish")


class Stage:
  def __init__(self, circuit, index, history_pool, flags):
    self.circuit, self.index = circuit, index
    name = f"stage{index}"
    self.half = Stack(history_pool, name + ".half", "cells")
    self.clock = Stack(history_pool, name + ".clock", "cells")
    self.alive_key, self.phase_key = name + ".alive", name + ".interval"
    self.alive = circuit.get(PREVIOUS, self.alive_key, (False, True), False).eq(True)
    self.interval = circuit.get(PREVIOUS, self.phase_key, tuple(range(7)), 0)
    self.birth_key, self.release_key = name + ".birth", name + ".release"
    self.birth = circuit.get(PREVIOUS, self.birth_key, (False, True), False).eq(True)
    self.release = circuit.get(PREVIOUS, self.release_key, (False, True), False).eq(True)
    self.batch_key = name + ".batch"
    self.batch = circuit.get(PREVIOUS, self.batch_key, (0, 1, 2, 3), 0)
    self.pending_key, self.middle_key = name + ".pending", name + ".middle"
    self.pending = circuit.get(PREVIOUS, self.pending_key, (False, True), False).eq(True)
    self.middle = circuit.get(PREVIOUS, self.middle_key, (False, True), False).eq(True)
    self.results = [Stack(flags.flag_pool, f"{name}.result{i}", "cells") for i in range(4)]

  def answering(self):
    return conjunction(self.alive, disjunction(*(self.interval.eq(i) for i in range(2, 6))))

  def advance(self, enabled, birth, half):
    self.clock.drop(conjunction(enabled, self.alive))
    boundary = conjunction(enabled, self.alive, self.clock.empty())
    self.interval = Value.select(boundary, self.interval.map(lambda n: min(n + 1, 6)), self.interval)
    self.clock.copy_from(self.half, boundary)
    release = conjunction(boundary, disjunction(*(self.interval.eq(i) for i in range(1, 5))))
    self.circuit.require(neg(self.pending), release)
    self.release = choose(enabled, release, self.release)
    self.batch = Value.select(release, self.interval.map(lambda n: max(0, min(3, n - 1))).recode((0, 1, 2, 3)), self.batch)
    self.alive = conjunction(self.alive, neg(conjunction(boundary, self.interval.eq(6))))
    self.half.copy_from(half, birth)
    self.clock.copy_from(half, birth)
    self.interval = Value.select(birth, Value.constant(0), self.interval)
    self.alive = disjunction(self.alive, birth)
    self.release = conjunction(self.release, neg(birth))
    self.birth = choose(enabled, birth, self.birth)
    self.pending = conjunction(self.pending, neg(birth))
    for result in self.results:
      result.clear(birth)

  def started(self, enabled):
    self.pending = disjunction(self.pending, conjunction(enabled, self.release))

  def capture(self, worker):
    done = conjunction(self.pending, worker.phase.eq("done"))
    for index, result in enumerate(self.results):
      result.copy_from(worker.flags, conjunction(done, self.batch.eq(index)))
    self.pending = conjunction(self.pending, neg(done))

  def consume(self, enabled):
    active = conjunction(enabled, self.answering())
    answers = []
    for index, result in enumerate(self.results):
      take = conjunction(active, self.interval.eq(index + 2))
      self.circuit.require(neg(result.empty()), take)
      _, bit = result.pop(take)
      answers.append(conjunction(take, bit.eq(True)))
    self.middle = choose(enabled, disjunction(*answers), self.middle)

  def finalize(self):
    self.half.finalize()
    self.clock.finalize()
    for result in self.results:
      result.finalize()
    for key, value in ((self.phase_key, self.interval), (self.batch_key, self.batch)):
      self.circuit.put(key, value)
    for key, value in ((self.alive_key, self.alive), (self.birth_key, self.birth),
                       (self.release_key, self.release), (self.pending_key, self.pending),
                       (self.middle_key, self.middle)):
      self.circuit.put(key, Value.select(value, Value.constant(True), Value.constant(False)))


class DelayedPAL:
  def __init__(self, circuit, rates=DEFAULT, dual_flags=False):
    self.circuit, self.rates = circuit, rates
    if rates.k != 8 or rates.flags < rates.matching or rates.flags < 32 or rates.flags & (rates.flags - 1):
      raise ValueError("the current worker tables require the derived k=8 power-of-two schedule")
    self.matchers = [GSMatcher(circuit, f"match{i}") for i in range(2)]
    self.flags = [GSFlags(circuit, f"flag{i}", dual=dual_flags) for i in range(2)]
    history = self.matchers[0].streams.cells
    self.power = Stack(history, "pal.power", "cells")
    self.next_birth = Stack(history, "pal.next_birth", "cells")
    self.stages = [Stage(circuit, i, history, self.flags[i]) for i in range(2)]
    self.phase = circuit.get(PREVIOUS, "pal.phase", PHASES, "ready")
    self.clock = circuit.get(PREVIOUS, "pal.clock", tuple(range(rates.flags)), 0)
    self.power_ready = circuit.get(PREVIOUS, "pal.power_ready", (False, True), False).eq(True)
    self.slot = circuit.get(PREVIOUS, "pal.slot", (0, 1), 0)
    self.small = circuit.get(PREVIOUS, "pal.small", (0, 1, 2, 3, 4), 0)
    self.first = circuit.get(PREVIOUS, "pal.first", (False, True), False)
    self.current = circuit.get(PREVIOUS, "pal.current", (False, True), False)
    self.output = circuit.get(PREVIOUS, "pal.output", (False, True), True).eq(True)

  def tick(self):
    c, phase = self.circuit, self.phase
    ready = phase.eq("ready")
    self.first = Value.select(conjunction(ready, self.small.eq(0)),
                              Value.select(c.input().eq("b"), Value.constant(True), Value.constant(False)), self.first)
    self.current = Value.select(ready, Value.select(c.input().eq("b"), Value.constant(True), Value.constant(False)), self.current)
    self.small = Value.select(ready, self.small.map(lambda n: min(4, n + 1)), self.small)
    # An actual input cell may be installed during a later delivery step;
    # every local node carries the held byte consistently during this round.
    byte = Value.select(self.current.eq(True), Value.constant("b"), Value.constant("a"))
    c.put("input", byte, tuple("ab"), "a")
    nothing, work = Value.constant(None), Value.constant(".")
    matching_clock = conjunction(*(neg(bit) for bit in self.clock.bits[(self.rates.matching - 1).bit_length():]))
    for stage, matcher, flags in zip(self.stages, self.matchers, self.flags):
      matcher_command = nothing
      matcher_command = Value.select(conjunction(phase.eq("settle"), matcher.phase.eq("maintenance")), work, matcher_command)
      matcher_command = Value.select(phase.eq("deliver"), byte, matcher_command)
      matcher_command = Value.select(conjunction(phase.eq("broadcast"),
                     disjunction(matcher.phase.eq("broadcast"), matcher.phase.eq("maintenance"))), work, matcher_command)
      matcher_command = Value.select(conjunction(phase.eq("start"), stage.birth), Value.constant("!"), matcher_command)
      matcher_command = Value.select(conjunction(phase.eq("serve"), matching_clock), work, matcher_command)

      flags_command = nothing
      flags_command = Value.select(conjunction(phase.eq("settle"), flags.phase.eq("maintenance")), work, flags_command)
      flags_command = Value.select(conjunction(phase.eq("deliver"), stage.alive), byte, flags_command)
      flags_command = Value.select(conjunction(phase.eq("broadcast"),
                    disjunction(flags.phase.eq("broadcast"), flags.phase.eq("maintenance"))), work, flags_command)
      flags_command = Value.select(conjunction(phase.eq("start"), stage.release), Value.constant("!"), flags_command)
      flags_command = Value.select(conjunction(phase.eq("start"), stage.birth), Value.constant("^"), flags_command)
      flags_command = Value.select(conjunction(phase.eq("mark"), stage.release), Value.constant("|"), flags_command)
      flags_command = Value.select(phase.eq("serve"), work, flags_command)
      matcher.tick(matcher_command)
      flags.tick(flags_command)
      stage.started(phase.eq("start"))
      stage.capture(flags)

    clocking = phase.eq("clock")
    first = conjunction(clocking, neg(self.power_ready))
    self.next_birth.drop(conjunction(clocking, self.power_ready))
    birth = conjunction(clocking, self.power_ready, self.next_birth.empty())
    for index, stage in enumerate(self.stages):
      stage.advance(clocking, conjunction(birth, self.slot.eq(index)), self.power)
      stage.consume(phase.eq("middle"))
    history = self.matchers[0].end.left
    self.power.copy_from(history, disjunction(first, birth))
    self.next_birth.copy_from(history, disjunction(first, birth))
    self.power_ready = disjunction(self.power_ready, first)
    self.slot = Value.select(birth, self.slot.cycle(), self.slot)

    active = [stage.answering() for stage in self.stages]
    c.require(conjunction(disjunction(*active), neg(conjunction(*active))),
              conjunction(phase.eq("finish"), self.small.eq(4)))
    ordinary = disjunction(*(conjunction(is_active, stage.middle, matcher.output)
                             for is_active, stage, matcher in zip(active, self.stages, self.matchers)))
    small = disjunction(self.small.eq(1), conjunction(disjunction(self.small.eq(2), self.small.eq(3)),
                                                    self.first.equal(self.current)))
    answer = choose(self.small.eq(4), ordinary, small)
    self.output = choose(phase.eq("finish"), answer, self.output)

    timed = disjunction(*(phase.eq(name) for name in ("settle", "broadcast", "serve")))
    last = disjunction(conjunction(phase.eq("settle"), self.clock.eq(2)),
                       conjunction(phase.eq("broadcast"), self.clock.eq(28)),
                       conjunction(phase.eq("serve"), self.clock.eq(self.rates.flags - 1)))
    advance = disjunction(neg(timed), last)
    self.clock = Value.select(conjunction(timed, neg(last)), self.clock.cycle(), Value.constant(0))
    self.phase = Value.select(advance, phase.map(lambda name: PHASES[(PHASES.index(name) + 1) % len(PHASES)]), phase)

  def finalize(self):
    for matcher, flags, stage in zip(self.matchers, self.flags, self.stages):
      matcher.finalize()
      flags.finalize()
      stage.finalize()
    self.power.finalize()
    self.next_birth.finalize()
    for key, value in (("pal.phase", self.phase), ("pal.clock", self.clock), ("pal.slot", self.slot),
                       ("pal.small", self.small), ("pal.first", self.first), ("pal.current", self.current)):
      self.circuit.put(key, value)
    self.circuit.put("pal.power_ready", Value.select(self.power_ready, Value.constant(True), Value.constant(False)))
    self.circuit.put("pal.output", Value.select(self.output, Value.constant(True), Value.constant(False)))


def build(rates=None, dual_flags=True):
  if rates is None:
    rates = DEFAULT_DUAL if dual_flags else DEFAULT
  circuit = Circuit("ab")
  machine = DelayedPAL(circuit, rates, dual_flags=dual_flags)
  machine.tick()
  machine.finalize()
  scaffold = circuit.machine(conjunction(machine.phase.eq("ready"), machine.output), initial_accepting=True)
  return circuit, machine, scaffold
