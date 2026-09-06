"""Predicated lowering of the match-paced DP search used by scaffold_galil."""
from fpp_finite import LEFT, END
from fpp_subroutine import SOURCE
from dp_finite import LOWER
from scaffold_circuit import (Value, PREVIOUS, TRUE, FALSE, choose,
                              conjunction as AND, disjunction as OR, neg as NOT)
from scaffold_circuit_structs import Counter


class Search:
  MODES = ("idle", "grow", "lower", "lower_home", "copy", "home", "run",
           "found", "missed", "wait", "double")

  def __init__(self, circuit, program, pool, center, walker, radius):
    self.circuit, self.program = circuit, program
    self.center, self.walker, self.radius = center, walker, radius
    self.lower, self.span, self.work, self.debt = [Counter(pool, "sp." + name)
                                                for name in ("lo", "span", "work", "debt")]
    self.mode = circuit.get(PREVIOUS, "sp.mode", self.MODES, "idle")
    self.final = circuit.get(PREVIOUS, "sp.final", (False, True), False).eq(True)
    self.quarter = circuit.get(PREVIOUS, "sp.quarter", tuple(range(4)), 0)

  def active(self):
    return NOT(OR(self.mode.eq("idle"), self.mode.eq("found"), self.mode.eq("missed")))

  def set_mode(self, value, enabled):
    self.mode = Value.select(enabled, Value.constant(value), self.mode)

  def start(self, lower, enabled=TRUE):
    self.circuit.require(AND(NOT(lower.negative()), NOT(self.radius.negative()),
                             NOT(self.center.read().eq(None))), enabled)
    self.program.reset(enabled)
    self.lower.copy_from(lower, enabled)
    self.work.copy_from(lower, enabled)
    self.work.inc(AND(enabled, self.work.zero()))
    self.span.reset(enabled)
    self.debt.pos.copy_from(self.radius.neg, enabled)
    self.debt.neg.copy_from(self.radius.pos, enabled)
    self.set_mode("grow", enabled)
    self.final = choose(enabled, FALSE, self.final)
    self.quarter = Value.select(enabled, Value.constant(0), self.quarter)

  def advance_match(self, enabled=TRUE):
    self.circuit.require(self.active(), enabled)
    self.radius.inc(enabled); self.debt.dec(enabled)

  def prepare(self, enabled):
    self.program.reset(enabled)
    self.walker.copy_from(self.center, enabled)
    self.work.copy_from(self.lower, enabled)
    tape = self.program.tapes[LOWER]
    tape.write(Value.constant(LEFT), enabled); tape.move(1, enabled)
    self.set_mode("lower", enabled)
    self.final = choose(enabled, FALSE, self.final)

  def double(self, enabled):
    self.work.copy_from(self.span, enabled)
    self.span.reset(enabled)
    self.quarter = Value.select(enabled, Value.constant(0), self.quarter)
    self.set_mode("double", enabled)

  def run_step(self, enabled):
    self.program.step(enabled)
    finished = AND(enabled, self.program.done)
    self.circuit.require(NOT(self.debt.negative()), finished)
    found = AND(finished, self.program.pc.eq(self.program.kernel.found))
    missed = AND(finished, NOT(found), self.final)
    next_stage = AND(finished, NOT(found), NOT(self.final), self.debt.zero())
    wait = AND(finished, NOT(found), NOT(self.final), NOT(self.debt.zero()))
    self.set_mode("found", found); self.set_mode("missed", missed)
    self.double(next_stage)
    self.set_mode("wait", wait)

  def step(self, enabled=TRUE):
    mode = {name: AND(enabled, self.mode.eq(name)) for name in self.MODES}
    source, lower = self.program.tapes[SOURCE], self.program.tapes[LOWER]
    positive = self.work.positive()
    grow = AND(mode["grow"], positive)
    self.work.dec(grow)
    for _ in range(8): self.span.inc(grow)
    for _ in range(2): self.debt.inc(grow)
    self.prepare(AND(mode["grow"], NOT(positive)))

    positive = self.work.positive()
    loading = AND(mode["lower"], positive)
    lower.write(Value.constant("1"), loading); lower.move(1, loading)
    self.work.dec(loading)
    loaded = AND(mode["lower"], NOT(positive))
    lower.write(Value.constant(END), loaded)
    self.set_mode("lower_home", loaded)

    home = lower.focus.eq(LEFT)
    begin_copy = AND(mode["lower_home"], home)
    source.write(Value.constant(LEFT), begin_copy); source.move(1, begin_copy)
    self.work.copy_from(self.span, begin_copy); self.work.inc(begin_copy)
    self.set_mode("copy", begin_copy)
    lower.move(-1, AND(mode["lower_home"], NOT(home)))

    symbol = self.walker.read()
    end = OR(symbol.eq(None), self.work.zero())
    stop_copy = AND(mode["copy"], end)
    source.write(Value.constant(END), stop_copy)
    self.final = choose(stop_copy, symbol.eq(None), self.final)
    self.set_mode("home", stop_copy)
    copying = AND(mode["copy"], NOT(end))
    source.write(Value({s: guard for s, guard in symbol.cases.items() if s is not None}), copying)
    source.move(1, copying); self.walker.left(copying); self.work.dec(copying)

    home = source.focus.eq(LEFT)
    start_run = AND(mode["home"], home)
    self.program.start(start_run); self.set_mode("run", start_run)
    source.move(-1, AND(mode["home"], NOT(home)))
    self.run_step(mode["run"])

    self.circuit.require(NOT(self.debt.negative()), mode["wait"])
    self.double(AND(mode["wait"], self.debt.zero()))

    positive = self.work.positive()
    doubling = AND(mode["double"], positive)
    quarter_end = self.quarter.eq(3)
    self.work.dec(doubling); self.span.inc(doubling); self.span.inc(doubling)
    self.quarter = Value.select(doubling, self.quarter.map(lambda n: (n + 1) % 4), self.quarter)
    self.debt.inc(AND(doubling, quarter_end))
    self.prepare(AND(mode["double"], NOT(positive)))

  def tick(self, enabled=TRUE, quantum=64):
    running = self.mode.eq("run")
    self.step(enabled)
    for _ in range(1, quantum):
      self.run_step(AND(enabled, running, self.mode.eq("run")))

  def finalize(self):
    self.program.finalize()
    for counter in (self.lower, self.span, self.work, self.debt): counter.finalize()
    self.circuit.put("sp.mode", self.mode)
    self.circuit.put("sp.final", Value.select(self.final, Value.constant(True), Value.constant(False)))
    self.circuit.put("sp.quarter", self.quarter)
