"""Finite-state lowering of the offline FPP kernel; not a real-time PAL PEG.

Generated instructions are only read/branch, write, unit move, emit and halt.
No callbacks, integer registers, dynamic call stack or address tests survive
construction. Each instruction is one simulated step on seven independent
tapes. Offline input loading and external decoding of emitted head positions
are deliberately separate. See FISCHER_PATERSON.md for the invariants.
"""
from dataclasses import dataclass

LEFT, END, BLANK = "^", "$", "_"
A, B, C, S, T, BACK, FRONT = range(7)


@dataclass
class Run:
  borders: tuple
  steps: int
  tapes: tuple = ()
  positions: tuple = ()
  state: int = -1


class Program:
  def __init__(self, alphabet, ntapes=7):
    self.ntapes = ntapes
    self.alphabet = tuple(alphabet)
    if not self.alphabet or len(set(self.alphabet)) != len(self.alphabet):
      raise ValueError("nonempty distinct alphabet required")
    if any(len(s) != 1 or s in (LEFT, END, BLANK, "0", "1")
           for s in self.alphabet):
      raise ValueError("alphabet collides with work-tape symbols")
    self.code = []
    self.start = None

  def reserve(self):
    self.code.append(None)
    return len(self.code) - 1

  def add(self, instruction):
    state = self.reserve()
    self.code[state] = instruction
    return state

  def move(self, tape, direction, next_state):
    return self.add(("move", tape, direction, next_state))

  def write(self, tape, symbol, next_state):
    return self.add(("write", tape, symbol, next_state))

  def branch(self, tape, choices, state=None):
    instruction = ("read", tape, dict(choices))
    if state is None:
      return self.add(instruction)
    assert self.code[state] is None
    self.code[state] = instruction
    return state

  def validate(self):
    assert isinstance(self.start, int)
    assert 0 <= self.start < len(self.code)
    for instruction in self.code:
      assert instruction is not None, "unresolved continuation"
      op = instruction[0]
      assert op in ("read", "move", "write", "emit", "halt")
      if op in ("read", "move", "write"):
        assert 0 <= instruction[1] < self.ntapes
      if op == "move":
        assert instruction[2] in (-1, 1)
      if op == "read":
        assert instruction[2]
        targets = instruction[2].values()
      elif op == "halt":
        targets = ()
      else:
        targets = (instruction[-1],)
      assert all(isinstance(q, int) and 0 <= q < len(self.code) for q in targets)

  def run(self, word, max_steps=None):
    if any(symbol not in self.alphabet for symbol in word):
      raise ValueError("input outside declared alphabet")
    z = dict(enumerate(LEFT + word + END))
    tapes = [z, dict(z), {0: "0", 1: "1", 2: "0"},
             {0: LEFT}, {0: LEFT}, {0: LEFT}, {0: LEFT}]
    positions = [0, 1, 0, 0, 0, 0, 0]
    # The cap is an external test watchdog, not part of the machine.
    if max_steps is None:
      max_steps = 1000 * (len(word) + 1)
    return self.execute(tapes, positions, max_steps)

  def execution(self, tapes, positions, start=None):
    return Execution(self, tapes, positions, self.start if start is None else start)

  def execute(self, tapes, positions, max_steps, start=None):
    """Run at most max_steps local instructions from a finite control state."""
    machine = self.execution(tapes, positions, start)
    while not machine.done:
      if machine.steps >= max_steps:
        raise RuntimeError("finite controller exceeded watchdog")
      machine.step()
    return machine.result()


class Execution:
  """Resumable VM; addresses and observation stay outside controller code."""
  def __init__(self, program, tapes, positions, state):
    assert len(tapes) == len(positions) == program.ntapes
    self.program, self.tapes, self.positions = program, tapes, positions
    self.state, self.steps, self.emitted, self.done = state, 0, [], False

  def step(self):
    if self.done:
      raise RuntimeError("cannot step a halted controller")
    row = self.program.code[self.state]
    op = row[0]
    self.steps += 1
    if op == "halt":
      self.done = True
      return
    if op == "emit":
      self.emitted.append(self.positions[A])
      self.state = row[1]
      return
    tape = row[1]
    if op == "read":
      symbol = self.tapes[tape].get(self.positions[tape], BLANK)
      if symbol not in row[2]:
        raise RuntimeError(f"undefined transition at {self.state}, tape {tape}: {symbol}")
      self.state = row[2][symbol]
    else:
      if op == "move":
        self.positions[tape] += row[2]
        if self.positions[tape] < 0:
          raise RuntimeError("left-end crossing")
      else:
        self.tapes[tape][self.positions[tape]] = row[2]
      self.state = row[3]

  def result(self):
    if not self.done:
      raise RuntimeError("controller has not halted")
    return Run(tuple(self.emitted), self.steps, tuple(self.tapes),
               tuple(self.positions), self.state)


class Builder:
  """Compile-time macros only; all continuations become integer state labels."""
  def __init__(self, program):
    self.p = program

  def push(self, tape, symbol, k):
    return self.p.move(tape, 1, self.p.write(tape, symbol, k))

  def pop(self, tape, k):
    return self.p.write(tape, BLANK, self.p.move(tape, -1, k))

  def copy_s_to_t(self, k):
    p = self.p
    back, restore = p.reserve(), p.reserve()
    p.branch(S, {BLANK: p.move(S, -1, k), "1": p.move(S, 1, restore)}, restore)
    p.branch(S, {LEFT: p.move(S, 1, restore),
                 "1": self.push(T, "1", p.move(S, -1, back))}, back)
    return back

  def delta_read(self, zero, one):
    p = self.p
    front, transfer = p.reserve(), p.reserve()
    outcomes = {"0": zero, "1": one}
    p.branch(FRONT, {LEFT: transfer, **{
      bit: self.pop(FRONT, p.write(C, bit, dest))
      for bit, dest in outcomes.items()}}, front)
    p.branch(BACK, {LEFT: front, **{
      bit: self.pop(BACK, self.push(FRONT, bit, transfer))
      for bit in outcomes}}, transfer)
    return p.branch(C, {"0": zero, "1": one, BLANK: front})

  def fallback(self, k, append_distance):
    p = self.p
    more, scan = p.reserve(), p.reserve()
    after_zero = p.move(A, -1, self.pop(T, more))
    if append_distance:
      after_zero = self.push(BACK, "1", after_zero)
    read = self.delta_read(after_zero, self.pop(S, p.move(C, -1, scan)))
    # Reserve a read-state alias with a finite branch on C. This dispatch
    # neither seeks nor changes a tape, and handles lazy materialization.
    p.branch(C, {symbol: read for symbol in ("0", "1", BLANK)}, scan)
    p.branch(T, {LEFT: k, "1": p.move(C, -1, scan)}, more)
    return self.copy_s_to_t(more)


def build_program(alphabet="ab#"):
  p = Program(alphabet)
  b = Builder(p)
  halt = p.add(("halt",))
  chain = p.reserve()
  emit = p.add(("emit", b.fallback(chain, False)))
  p.branch(A, {LEFT: halt, **{symbol: emit for symbol in alphabet}}, chain)
  next_input = p.reserve()
  advance = p.move(B, 1, next_input)
  matched_scan = p.reserve()
  scan = b.delta_read(advance, b.push(S, "1", p.move(C, 1, matched_scan)))
  p.branch(C, {symbol: scan for symbol in ("0", "1", BLANK)}, matched_scan)
  matched = b.push(BACK, "0", p.move(C, 1, matched_scan))
  failed_at_left = b.push(BACK, "1", b.push(BACK, "0", advance))
  choices = {END: chain}
  for symbol in alphabet:
    compare = p.reserve()
    retry = p.move(A, 1, compare)
    fallback = b.fallback(retry, True)
    failed = p.move(A, -1, p.branch(A, {
      LEFT: failed_at_left, **{c: fallback for c in alphabet}}))
    p.branch(A, {c: matched if c == symbol else failed
                 for c in (*alphabet, END)}, compare)
    choices[symbol] = retry
  p.branch(B, choices, next_input)
  p.start = p.branch(B, {END: halt, **{symbol: advance for symbol in alphabet}})
  p.validate()
  return p
