"""Persistent semiperiod prediction driven by the real DP unary answer.

The input walker copies C..C-h locally. A private tape then bounces between
its marked ends; a separate readonly verifier catches up with the already
matched radius. No coordinates, head identity tests, or numeric h are used.
The caller owns center shifts and must update the relative counters with
shift_one() for each real place moved by its center.
"""
from fpp_finite import LEFT
from scaffold_program import TapeView
from scaffold_search import alias
from scavm_structs import CounterView


class ChainView:
  def __init__(self, vm, previous, builder, center, walker, verifier, radius):
    self.vm, self.builder = vm, builder
    self.center, self.walker, self.verifier, self.radius = center, walker, verifier, radius
    self.period = TapeView(vm, previous, builder, "ch.p")
    self.h, self.lag, self.distance, self.boundary, self.last, self.margin, self.cycle = [
      CounterView(vm, previous, builder, "ch." + name)
      for name in ("h", "lag", "dist", "bound", "last", "margin", "cycle")]
    if previous is None:
      self.mode, self.direction, self.phase = "idle", 1, 0
      self.period_only = False
    else:
      lab = vm.label(previous)
      self.mode, self.direction, self.phase = lab["ch.mode"], lab["ch.dir"], lab["ch.phase"]
      self.period_only = lab["ch.only"]

  def start(self):
    self.period.reset()
    self.period.write("F:" + self.center.read())
    self.walker.copy_from(self.center)
    self.verifier.copy_from(self.center)
    self.h.reset()
    self.distance.reset()
    self.boundary.reset()
    self.last.reset()
    self.cycle.reset()
    self.period_only = False
    alias(self.lag, self.radius)
    alias(self.margin, self.radius)
    self.mode, self.direction, self.phase = "copy", 1, 0

  def prediction(self):
    if self.mode != "watch" or self.lag.sign() != 0:
      return None
    return self.period.read()[-1]

  def can_shift(self):
    ready = self.mode == "watch" and self.lag.sign() == 0 and self.phase == 4
    return ready and (self.cycle_end() if self.period_only else self.margin.sign() >= 0)

  def cycle_end(self):
    return (self.cycle.sign() > 0 and
            self.cycle.pos.below_of_top() is None)

  def check_pair(self, left):
    """Check the two-semiperiod continuation invariant against real input."""
    if self.period_only and self.mode == "watch" and self.lag.sign() == 0:
      if self.cycle.sign() <= 0:
        raise AssertionError("chain continuation exhausted without dispatch")
      same = left == self.prediction()
      if same == self.cycle_end():
        raise AssertionError("left head contradicts the two-semiperiod continuation")

  def begin_shift(self):
    self.period_only = True
    self.cycle.reset()

  def _consume(self):
    self.verifier.right()
    token = self.period.read()
    if self.verifier.read() != token[-1]:
      self.mode = "broken"
      return False
    self.distance.inc()
    if token.startswith(("F:", "T:")):
      alias(self.last, self.boundary)
      alias(self.boundary, self.distance)
      self.phase = min(4, self.phase + 1)
      self.direction = 1 if token.startswith("F:") else -1
    self.period.move(self.direction)
    return True

  def matched(self):
    """One new place has joined the matched interval (also on a chain shift)."""
    self.margin.inc()
    if self.period_only:
      self.cycle.dec()
    if self.mode == "watch" and self.lag.sign() == 0:
      self._consume()
    else:
      self.lag.inc()

  def shift_one(self):
    for counter in (self.distance, self.boundary, self.last, self.margin):
      counter.dec()
    self.cycle.inc()
    self.cycle.inc()

  def step(self, answer):
    if self.mode == "copy":
      if answer.read() == "1":
        answer.move(-1)
        self.h.inc()
        for _ in range(4):
          self.margin.dec()
        self.walker.left()
        symbol = self.walker.read()
        if symbol is None:
          raise ValueError("DP answer crosses the input origin")
        self.period.move(1)
        self.period.write(symbol)
      elif answer.read() == LEFT and self.h.sign() > 0:
        self.period.write("T:" + self.period.read())
        self.mode = "back"
      else:
        raise ValueError("positive unary DP answer required")
    elif self.mode == "back":
      if self.period.read().startswith("F:"):
        self.period.move(1)
        self.mode = "watch"
      else:
        self.period.move(-1)
    elif self.mode == "watch" and self.lag.sign() > 0:
      if self._consume():
        self.lag.dec()
    elif self.mode not in ("idle", "watch", "broken"):
      raise AssertionError(self.mode)

  def finalize(self):
    self.period.finalize()
    for counter in (self.h, self.lag, self.distance, self.boundary, self.last, self.margin, self.cycle):
      counter.finalize()
    self.builder.label.update({"ch.mode": self.mode, "ch.dir": self.direction,
                               "ch.phase": self.phase, "ch.only": self.period_only})
