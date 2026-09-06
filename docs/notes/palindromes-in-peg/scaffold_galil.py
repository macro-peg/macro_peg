"""Whole online scaffold PAL experiment with DP and actual periodic shifts.

All input positions and lengths are represented by readonly place heads and
unary persistent stacks. Real finite FPP instructions select odd palindromic
place suffixes; actual main1-style replay rebuilds search at the new center.
Confirmed chain predictions can move C by h and L by 2h through unit moves.

This is still an experimental online controller, NOT a certified real-time
PAL machine or a PEG. Two-semiperiod continuation has an explicit unary
countdown and checks the left-head/prediction invariant. The global
predictability/work proof and SCA-to-PEG output remain outstanding.
Default execution drains each arrival; a supplied budget can miss positives.

OnlineGalil exposes separate output and input-ready events. Its internal
trailing-gap work does not require another external input character. The
legacy step/run interfaces remain available for the earlier circuit fixtures.
"""
from dataclasses import dataclass

from scavm import VM, SELF
from scavm_structs import Builder, CounterView, emit
from scaffold_places import PlaceHead
from scaffold_program import ProgramView
from scaffold_search import SearchView, alias
from scaffold_chain import ChainView
from fpp_finite import LEFT, END
from fpp_subroutine import build_marked_program, SOURCE, MARKS
from dp_finite import build_dp_program, OUTPUT
from galil_clock import derive

FPP_QUANTUM = 64
DEFAULT_TIMING = derive(FPP_QUANTUM)
MATCH_DELAY = DEFAULT_TIMING.match_delay
REALTIME_BUDGET = 2048
FIRST = "first:1"


def _transition(vm, arrival, new_input, kernels, advance_trailing_gap,
                timing=DEFAULT_TIMING):
  """One local operation; gap availability is independent of external input."""
  fpp_kernel, dp_kernel = kernels if kernels is not None else (
    build_marked_program("abs"), build_dp_program("abs"))
  fpp_calls = shifts = restarts = replays = 0
  vm.begin()
  b = Builder()
  b.label["input"] = arrival
  right, left, center, walker, verifier = heads = [
    PlaceHead(vm, vm.top, b, name) for name in ("R", "L", "C", "W", "V")]
  if new_input:
    for head in heads:
      head.append(SELF)
  length, radius, remaining, replay, zero = counters = [
    CounterView(vm, vm.top, b, "g." + name)
    for name in ("len", "rad", "rem", "replay", "zero")]
  search = SearchView(vm, vm.top, b, dp_kernel, center, walker, radius)
  chain = ChainView(vm, vm.top, b, center, walker, verifier, radius)
  fpp = ProgramView(vm, vm.top, b, fpp_kernel, name="f")
  source, marks = fpp.tapes[SOURCE], fpp.tapes[MARKS]
  if vm.top is None:
    mode, clock, output, replaying, odd, pair = "init", timing.match_delay, False, False, False, 0
  else:
    lab = vm.label(vm.top)
    mode, clock, output = lab["g.mode"], int(lab["g.clock"]), lab["g.out"]
    replaying, odd, pair = lab["g.replaying"], lab["g.odd"], lab["g.pair"]

  if mode == "scan":
    # The quantum is a fixed finite unrolling, not a variable work drain.
    # Only instruction execution is batched; span growth runs once/tick.
    if chain.mode != "idle":
      chain.step(search.program.tapes[OUTPUT])
    elif search.mode not in ("idle", "found", "missed"):
      quantum = timing.quantum if search.mode == "run" else 1
      for _ in range(quantum):
        search.step()
        if search.mode != "run":
          break
      if search.mode == "found":
        chain.start()
    if chain.mode == "broken":
      if chain.margin.sign() >= 0 and chain.last.sign() > 0 and chain.lag.sign() == 0:
        search.start(chain.last)
        chain.mode = "idle"
        restarts += 1
        clock = timing.match_delay
      else:
        raise AssertionError("chain restart violates the confirmed-period invariant")

  if mode == "init":
    right.right()
    left.copy_from(right)
    center.copy_from(right)
    length.inc()
    search.start(zero)
    mode, output = "scan", True
  elif mode == "scan":
    available = replaying or (right.can_right() if advance_trailing_gap
                             else right.head.can_right())
    if available:
      clock -= 1
      if clock == 0:
        clock = timing.match_delay
        right.right()
        left.left()
        if search.mode not in ("idle", "found", "missed") and chain.mode == "idle":
          search.advance_match()
        else:
          radius.inc()
        chain.check_pair(left.read())
        if left.read() == right.read():
          length.inc()
          length.inc()
          if chain.mode != "idle":
            chain.matched()
          if replaying:
            replay.dec()
            if replay.sign() == 0:
              replaying = False
          if not right.gap:
            output = left.is_first()
        elif not replaying and chain.can_shift() and chain.prediction() == right.read():
          chain.matched()
          chain.begin_shift()
          length.inc()
          length.inc()
          alias(remaining, chain.h)
          shifts += 1
          mode = "shift"
        else:
          if replaying:
            raise AssertionError("FPP-selected palindrome failed during replay")
          fpp.reset()
          walker.copy_from(right)
          alias(remaining, length)
          remaining.inc()
          source.write(LEFT)
          source.move(1)
          search.mode = chain.mode = "idle"
          mode = "copy"
  elif mode == "shift":
    if remaining.sign() > 0:
      remaining.dec()
      center.right()
      left.right()
      left.right()
      radius.dec()
      length.dec()
      length.dec()
      chain.shift_one()
    else:
      mode = "scan"
      if not right.gap:
        output = left.is_first()
  elif mode == "copy":
    if remaining.sign() > 0:
      symbol = walker.read()
      if symbol is None:
        raise AssertionError("fallback window crossed the input origin")
      source.write(symbol)
      source.move(1)
      walker.left()
      remaining.dec()
    else:
      source.write(END)
      mode = "home"
  elif mode == "home":
    if source.read() == LEFT:
      fpp.start()
      fpp_calls += 1
      mode = "fpp"
    else:
      source.move(-1)
  elif mode == "fpp":
    for _ in range(timing.quantum):
      fpp.step()
      if fpp.done:
        marks.move(1)
        marks.write(FIRST)
        marks.move(1)
        mode = "mark_end"
        break
  elif mode == "mark_end":
    if marks.read() == END:
      marks.move(-1)
      odd, mode = False, "choose"  # old odd length plus one new place
    else:
      marks.move(1)
  elif mode == "choose":
    if odd and marks.read() in ("1", FIRST):
      left.copy_from(right)
      center.copy_from(right)
      length.reset()
      length.inc()
      radius.reset()
      pair, mode = 0, "rewind"
    else:
      marks.move(-1)
      odd = not odd
  elif mode == "rewind":
    if marks.read() == FIRST:
      fpp.reset()
      mode = "replay_start"
    else:
      marks.move(-1)
      left.left()
      length.inc()
      pair = 1 - pair
      if pair == 0:
        center.left()
        radius.inc()
  elif mode == "replay_start":
    # No coordinates or head equality: replay consumes the saved radius.
    alias(replay, radius)
    right.copy_from(center)
    left.copy_from(center)
    radius.reset()
    length.reset()
    length.inc()
    chain.mode = "idle"
    search.start(zero)
    replaying = replay.sign() > 0
    clock, mode = timing.match_delay, "scan"
    replays += 1
    if not replaying and not right.gap:
      output = left.is_first()
  else:
    raise AssertionError(mode)

  caught = (mode == "scan" and not replaying and not right.gap
            and not right.head.can_right())
  input_ready = (mode == "scan" and not replaying and right.gap
                 and not right.head.can_right())
  report = int(output) if caught else 0
  for head in heads:
    head.finalize()
  for counter in counters:
    counter.finalize()
  search.finalize()
  chain.finalize()
  fpp.finalize()
  b.label.update({"g.mode": mode, "g.clock": str(clock), "g.out": output,
                  "g.replaying": replaying, "g.odd": odd, "g.pair": pair})
  emit(vm, b)
  return report, caught, input_ready, {
    "fpp_calls": fpp_calls, "chain_shifts": shifts,
    "search_restarts": restarts, "replays": replays}


def step(vm, arrival, new_input, kernels=None):
  """Legacy tick boundary, retained for comparison with the earlier circuits."""
  report, caught, _, events = _transition(vm, arrival, new_input, kernels, False,
                                         DEFAULT_TIMING)
  return report, caught, events


@dataclass(frozen=True)
class OnlineStep:
  """An output event and a read boundary are independent observations.

  None means no output event; zero is a completed negative answer. Events
  are diagnostics and do not control execution.
  """
  output: int | None
  input_ready: bool
  events: dict


class OnlineGalil:
  """Event interface to the experimental online controller, without a budget.

  read consumes exactly one binary symbol and performs one local transition.
  work performs one local transition with no input symbol. It may be needed
  after an output to prepare the next read. Both return an OnlineStep.

  This fixes the input/output protocol, not the remaining correspondence and
  timing obligations of the underlying algorithm. There is no drain loop,
  external input buffer or palindrome oracle inside this object. Its extra control is
  two Boolean flags; VM and finite kernels hold the actual source machine.
  """
  def __init__(self, quantum=FPP_QUANTUM):
    self.vm = VM()
    self.kernels = build_marked_program("abs"), build_dp_program("abs")
    self.timing = derive(quantum)
    self.input_ready = True
    self._output_pending = False

  def read(self, char):
    if char not in ("a", "b"):
      raise ValueError("one binary input symbol required")
    if not self.input_ready:
      raise ValueError("source is not ready to read another symbol")
    self._output_pending = True
    return self._advance(char, True)

  def work(self):
    if self.input_ready:
      raise ValueError("source is waiting for an input symbol")
    return self._advance(None, False)

  def _advance(self, char, new_input):
    report, caught, ready, events = _transition(
      self.vm, char, new_input, self.kernels, True, self.timing)
    output = report if caught and self._output_pending else None
    if output is not None:
      self._output_pending = False
    if ready and self._output_pending:
      raise AssertionError("source requested another input before emitting its answer")
    self.input_ready = ready
    return OnlineStep(output, ready, events)


def run(word, budget=None):
  if any(c not in "ab" for c in word):
    raise ValueError("binary input required")
  if budget is not None and (type(budget) is not int or budget < 1):
    raise ValueError("positive integer budget required")
  kernels = build_marked_program("abs"), build_dp_program("abs")
  vm, reports, costs = VM(), [], []
  totals = dict.fromkeys(("fpp_calls", "chain_shifts", "search_restarts", "replays"), 0)
  for arrival in word:
    ticks, caught = 0, False
    while (not caught if budget is None else ticks < budget):
      report, caught, events = step(vm, arrival, ticks == 0, kernels)
      for name, value in events.items(): totals[name] += value
      ticks += 1
    reports.append(report)
    costs.append(ticks)
  return reports, {**vm.stats(), **totals, "costs": costs,
                   "max_microsteps": max(costs, default=0)}


def recognize(word):
  """Fixed-budget scaffold candidate; this is a Python machine, not a PEG."""
  reports, _ = run(word, budget=REALTIME_BUDGET)
  return not word or bool(reports[-1])
