"""Lower semiperiod verification and actual continuation counters to equations."""
from fpp_finite import LEFT, BLANK
from scaffold_circuit import (Value, PREVIOUS, TRUE, FALSE, choose,
                              conjunction as AND, disjunction as OR, neg as NOT)
from scaffold_circuit_structs import Counter, Tape


class Chain:
  MODES = ("idle", "copy", "back", "watch", "broken")

  def __init__(self, circuit, pool, center, walker, verifier, radius):
    self.circuit = circuit
    self.center, self.walker, self.verifier, self.radius = center, walker, verifier, radius
    symbols = (BLANK, LEFT, "a", "b", "s", "F:a", "F:b", "F:s", "T:a", "T:b", "T:s")
    self.period = Tape(circuit, "ch.p", symbols, slots=4)
    self.h, self.lag, self.distance, self.boundary, self.last, self.margin, self.cycle = [
      Counter(pool, "ch." + name) for name in ("h", "lag", "dist", "bound", "last", "margin", "cycle")]
    self.mode = circuit.get(PREVIOUS, "ch.mode", self.MODES, "idle")
    self.direction = circuit.get(PREVIOUS, "ch.dir", (-1, 1), 1)
    self.phase = circuit.get(PREVIOUS, "ch.phase", tuple(range(5)), 0)
    self.only = circuit.get(PREVIOUS, "ch.only", (False, True), False).eq(True)

  def set_mode(self, value, enabled):
    self.mode = Value.select(enabled, Value.constant(value), self.mode)

  def start(self, enabled=TRUE):
    symbol = self.center.read()
    self.circuit.require(NOT(symbol.eq(None)), enabled)
    marked = Value({"F:" + s: guard for s, guard in symbol.cases.items() if s is not None})
    self.period.reset(enabled); self.period.write(marked, enabled)
    self.walker.copy_from(self.center, enabled); self.verifier.copy_from(self.center, enabled)
    for counter in (self.h, self.distance, self.boundary, self.last, self.cycle): counter.reset(enabled)
    self.only = choose(enabled, FALSE, self.only)
    self.lag.copy_from(self.radius, enabled); self.margin.copy_from(self.radius, enabled)
    self.set_mode("copy", enabled)
    self.direction = Value.select(enabled, Value.constant(1), self.direction)
    self.phase = Value.select(enabled, Value.constant(0), self.phase)

  def prediction(self):
    ready = AND(self.mode.eq("watch"), self.lag.zero())
    return Value.select(ready, self.period.focus.map(lambda s: s[-1]), Value.constant(None))

  def cycle_end(self):
    below = self.cycle.pos.pool.pointer(self.cycle.pos.top, self.cycle.pos.tag, "below")
    return AND(self.cycle.positive(), NOT(below.present()))

  def can_shift(self):
    return AND(self.mode.eq("watch"), self.lag.zero(), self.phase.eq(4),
               choose(self.only, self.cycle_end(), NOT(self.margin.negative())))

  def check_pair(self, left, enabled=TRUE):
    checking = AND(enabled, self.only, self.mode.eq("watch"), self.lag.zero())
    same = left.equal(self.prediction())
    self.circuit.require(AND(self.cycle.positive(), choose(self.cycle_end(), NOT(same), same)), checking)

  def begin_shift(self, enabled=TRUE):
    self.only = choose(enabled, TRUE, self.only)
    self.cycle.reset(enabled)

  def consume(self, enabled):
    self.verifier.right(enabled)
    token = self.period.focus
    same = self.verifier.read().equal(token.map(lambda s: s[-1]))
    matched = AND(enabled, same)
    self.set_mode("broken", AND(enabled, NOT(same)))
    self.distance.inc(matched)
    first = OR(*(guard for value, guard in token.cases.items() if value.startswith("F:")))
    last = OR(*(guard for value, guard in token.cases.items() if value.startswith("T:")))
    boundary = AND(matched, OR(first, last))
    self.last.copy_from(self.boundary, boundary)
    self.boundary.copy_from(self.distance, boundary)
    self.phase = Value.select(boundary, self.phase.map(lambda n: min(4, n + 1)), self.phase)
    self.direction = Value.select(boundary,
      Value.select(first, Value.constant(1), Value.constant(-1)), self.direction)
    self.period.move(1, AND(matched, self.direction.eq(1)))
    self.period.move(-1, AND(matched, self.direction.eq(-1)))
    return matched

  def matched(self, enabled=TRUE):
    self.margin.inc(enabled)
    self.cycle.dec(AND(enabled, self.only))
    ready = AND(self.mode.eq("watch"), self.lag.zero())
    self.consume(AND(enabled, ready))
    self.lag.inc(AND(enabled, NOT(ready)))

  def shift_one(self, enabled=TRUE):
    for counter in (self.distance, self.boundary, self.last, self.margin): counter.dec(enabled)
    self.cycle.inc(enabled); self.cycle.inc(enabled)

  def step(self, answer, enabled=TRUE):
    copying, backing, watching = (AND(enabled, self.mode.eq(name)) for name in ("copy", "back", "watch"))
    more = answer.focus.eq("1")
    copy = AND(copying, more)
    done = AND(copying, NOT(more))
    self.circuit.require(AND(answer.focus.eq(LEFT), self.h.positive()), done)
    answer.move(-1, copy)
    self.h.inc(copy)
    for _ in range(4): self.margin.dec(copy)
    self.walker.left(copy)
    symbol = self.walker.read()
    self.circuit.require(NOT(symbol.eq(None)), copy)
    self.period.move(1, copy)
    self.period.write(Value({s: guard for s, guard in symbol.cases.items() if s is not None}), copy)
    # Only plain endpoint letters are reachable under this guard.
    marked = Value({"T:" + s: guard for s, guard in self.period.focus.cases.items() if s in "abs"})
    self.period.write(marked, done); self.set_mode("back", done)

    first = OR(*(guard for value, guard in self.period.focus.cases.items() if value.startswith("F:")))
    self.period.move(1, AND(backing, first))
    self.period.move(-1, AND(backing, NOT(first)))
    self.set_mode("watch", AND(backing, first))
    caught = self.consume(AND(watching, self.lag.positive()))
    self.lag.dec(caught)

  def finalize(self):
    self.period.finalize()
    for counter in (self.h, self.lag, self.distance, self.boundary, self.last, self.margin, self.cycle):
      counter.finalize()
    self.circuit.put("ch.mode", self.mode)
    self.circuit.put("ch.dir", self.direction)
    self.circuit.put("ch.phase", self.phase)
    self.circuit.put("ch.only", Value.select(self.only, Value.constant(True), Value.constant(False)))
