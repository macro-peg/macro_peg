"""Match-paced doubling DP search on persistent scaffold state.

Each step performs bounded local work, including at most one instruction of
the finite FPP/DP table. Missed non-final stages wait until the match radius is
ell/4 before preparing the next window of radius 2*ell. All lengths and the
remaining distance to that barrier are unary stacks, never host coordinates.

This is a scheduler component, not the completed Galil PAL controller. The
caller must provide a sufficiently slow match clock and the main(C,r) entry
conditions. A missed stage deadline is rejected, not hidden by an offline
drain. No claim that a particular clock suffices for all words is made here.
The window walker and center are readonly InputHead-compatible views; callers
must distribute arrivals to both and finalize both on every scaffold tick.
"""
from fpp_finite import LEFT, END
from fpp_subroutine import SOURCE
from dp_finite import LOWER
from scaffold_program import ProgramView
from scavm_structs import CounterView


def alias(target, source):
  target.pos.copy_from(source.pos)
  target.neg.copy_from(source.neg)


class SearchView:
  def __init__(self, vm, previous, builder, kernel, center, walker, radius):
    self.vm, self.builder = vm, builder
    self.center, self.walker, self.radius = center, walker, radius
    self.program = ProgramView(vm, previous, builder, kernel, name="dp")
    self.lower, self.span, self.work, self.debt = [
      CounterView(vm, previous, builder, "sp." + name)
      for name in ("lo", "span", "work", "debt")]
    if previous is None:
      self.mode, self.final, self.quarter = "idle", False, 0
    else:
      label = vm.label(previous)
      self.mode = label["sp.mode"]
      self.final, self.quarter = label["sp.final"], label["sp.quarter"]

  def start(self, lower):
    if lower.sign() < 0 or self.radius.sign() < 0:
      raise ValueError("nonnegative lower bound and match radius required")
    if self.center.read() is None:
      raise ValueError("center must be a real input place")
    self.program.reset()
    alias(self.lower, lower)
    alias(self.work, lower)
    if self.work.sign() == 0:
      self.work.inc()
    self.span.reset()
    # Begin at -radius. Grow adds 2*max(r,1), while concurrent match
    # events subtract one; the normalized signed counter stays exact.
    self.debt.pos.copy_from(self.radius.neg)
    self.debt.neg.copy_from(self.radius.pos)
    self.mode, self.final, self.quarter = "grow", False, 0

  def advance_match(self):
    """Account for one matched place, once per caller's finite clock event."""
    if self.mode in ("idle", "found", "missed"):
      raise ValueError("no active search")
    self.radius.inc()
    self.debt.dec()

  def _prepare(self):
    self.program.reset()
    self.walker.copy_from(self.center)
    alias(self.work, self.lower)
    tape = self.program.tapes[LOWER]
    tape.write(LEFT)
    tape.move(1)
    self.mode, self.final = "lower", False

  def _double(self):
    alias(self.work, self.span)
    self.span.reset()
    self.quarter, self.mode = 0, "double"

  def step(self):
    source, lower = self.program.tapes[SOURCE], self.program.tapes[LOWER]
    if self.mode == "grow":
      if self.work.sign() > 0:
        self.work.dec()
        for _ in range(8):
          self.span.inc()
        for _ in range(2):
          self.debt.inc()
      else:
        self._prepare()
    elif self.mode == "lower":
      if self.work.sign() > 0:
        lower.write("1")
        lower.move(1)
        self.work.dec()
      else:
        lower.write(END)
        self.mode = "lower_home"
    elif self.mode == "lower_home":
      if lower.read() == LEFT:
        source.write(LEFT)
        source.move(1)
        alias(self.work, self.span)
        self.work.inc()  # include the center itself
        self.mode = "copy"
      else:
        lower.move(-1)
    elif self.mode == "copy":
      symbol = self.walker.read()
      if symbol is None or self.work.sign() == 0:
        source.write(END)
        self.final = symbol is None
        self.mode = "home"
      else:
        source.write(symbol)
        source.move(1)
        self.walker.left()
        self.work.dec()
    elif self.mode == "home":
      if source.read() == LEFT:
        self.program.start()
        self.mode = "run"
      else:
        source.move(-1)
    elif self.mode == "run":
      self.program.step()
      if self.program.done:
        if self.debt.sign() < 0:
          raise RuntimeError("match passed the DP stage deadline")
        if self.program.pc == self.program.program.found:
          self.mode = "found"
        elif self.final:
          self.mode = "missed"
        elif self.debt.sign() == 0:
          self._double()
        else:
          self.mode = "wait"
    elif self.mode == "wait":
      if self.debt.sign() < 0:
        raise RuntimeError("match passed the DP waiting barrier")
      if self.debt.sign() == 0:
        self._double()
    elif self.mode == "double":
      if self.work.sign() > 0:
        self.work.dec()
        self.span.inc()
        self.span.inc()
        self.quarter = (self.quarter + 1) % 4
        if self.quarter == 0:
          self.debt.inc()
      else:
        self._prepare()
    elif self.mode not in ("idle", "found", "missed"):
      raise AssertionError(self.mode)

  def finalize(self):
    self.program.finalize()
    for counter in (self.lower, self.span, self.work, self.debt):
      counter.finalize()
    self.builder.label.update({"sp.mode": self.mode, "sp.final": self.final,
                               "sp.quarter": self.quarter})
