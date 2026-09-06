"""Lower real finite FPP/DP instruction tables to symbolic scaffold equations."""
from fpp_finite import BLANK
from scaffold_circuit import (Value, PREVIOUS, TRUE, FALSE, conjunction,
                              disjunction, neg, choose)
from scaffold_circuit_structs import Tape, StackPool
from read_blocks import analyze


class Program:
  def __init__(self, circuit, kernel, name, quantum=1, extra_symbols=(), coarse=False,
               shared_cells=False, extra_moves=0):
    self.circuit, self.kernel, self.name = circuit, kernel, name
    self.blocks = analyze(kernel) if coarse else None
    alphabets = [{BLANK, *(extra_symbols.get(t, ()) if isinstance(extra_symbols, dict) else extra_symbols)}
                 for t in range(kernel.ntapes)]
    for row in kernel.code:
      if row[0] == "read": alphabets[row[1]].update(row[2])
      if row[0] == "write": alphabets[row[1]].add(row[2])
    if shared_cells and coarse:
      raise ValueError("shared instruction cells require one raw instruction per step")
    self.shared_pool = None
    self.instruction_index, self.quantum = 0, quantum
    if shared_cells:
      self.shared_pool = StackPool(circuit, {"cells": quantum + extra_moves},
                                   tuple(sorted(set().union(*alphabets))))
      # Explicit instruction slots occupy the prefix. Moves requested by the
      # surrounding controller receive distinct slots after that prefix.
      self.shared_pool.used["cells"] = quantum
    self.tapes = [Tape(circuit, f"{name}.t{t}", tuple(sorted(alphabets[t])),
                      slots=quantum if self.blocks is None else
                      tuple(max(1, quantum * n) for n in self.blocks["bounds"][2*t:2*t+2]),
                      pool=self.shared_pool)
                  for t in range(kernel.ntapes)]
    self.pc_key, self.done_key = name + ".pc", name + ".done"
    self.pc = circuit.get(PREVIOUS, self.pc_key, tuple(range(len(kernel.code))), kernel.start)
    self.done = circuit.get(PREVIOUS, self.done_key, (False, True), True).eq(True)

  def start(self, enabled=TRUE, entry=None):
    entry = self.kernel.start if entry is None else entry
    self.pc = Value.select(enabled, Value.constant(entry), self.pc)
    self.done = choose(enabled, FALSE, self.done)

  def reset(self, enabled=TRUE):
    for tape in self.tapes: tape.reset(enabled)
    self.pc = Value.select(enabled, Value.constant(self.kernel.start), self.pc)
    self.done = choose(enabled, TRUE, self.done)

  def step(self, enabled=TRUE):
    if self.blocks is None:
      self.instruction_step(enabled)
    else:
      self.block_step(enabled)

  def instruction_step(self, enabled=TRUE):
    slot = None
    if self.shared_pool is not None:
      if self.instruction_index >= self.quantum:
        raise ValueError("finite instruction cell layout exhausted")
      slot = self.instruction_index
      self.instruction_index += 1
    active = conjunction(enabled, neg(self.done))
    events = [conjunction(active, self.pc.eq(i)) for i in range(len(self.kernel.code))]
    next_pc, halts = {}, []
    writes = [{} for tape in self.tapes]
    moves = [{-1: [], 1: []} for tape in self.tapes]
    for event, row in zip(events, self.kernel.code):
      op = row[0]
      if op == "halt":
        halts.append(event)
      elif op == "read":
        for symbol, target in row[2].items():
          branch = conjunction(event, self.tapes[row[1]].focus.eq(symbol))
          next_pc[target] = disjunction(next_pc.get(target, FALSE), branch)
      elif op in ("write", "move"):
        next_pc[row[3]] = disjunction(next_pc.get(row[3], FALSE), event)
        if op == "write":
          writes[row[1]][row[2]] = disjunction(writes[row[1]].get(row[2], FALSE), event)
        else:
          moves[row[1]][row[2]].append(event)
      else:
        raise ValueError("only finite read/write/move/halt instructions can be lowered")
    for tape, values, directions in zip(self.tapes, writes, moves):
      if values:
        tape.write(Value(values), disjunction(*values.values()))
      tape.move(-1, disjunction(*directions[-1]), slot=slot)
      tape.move(1, disjunction(*directions[1]), slot=slot)
    halt = disjunction(*halts)
    self.pc = Value.select(conjunction(active, neg(halt)), Value(next_pc), self.pc)
    self.done = disjunction(self.done, halt)

  def block_step(self, enabled=TRUE):
    active = conjunction(enabled, neg(self.done))
    events = [conjunction(active, self.pc.eq(i)) for i in range(len(self.kernel.code))]
    next_pc, halts = {}, []
    bases = {(t, side): stack.pool.used.get(stack.name, 0)
             for t, tape in enumerate(self.tapes) for side, stack in enumerate((tape.left, tape.right))}

    def forward(target, event):
      if target in self.blocks["cuts"]:
        next_pc[target] = disjunction(next_pc.get(target, FALSE), event)
      else:
        events[target] = disjunction(events[target], event)

    for state in self.blocks["order"]:
      event, row = events[state], self.kernel.code[state]
      op = row[0]
      if op == "halt":
        halts.append(event)
      elif op == "read":
        for symbol, target in row[2].items():
          forward(target, conjunction(event, self.tapes[row[1]].focus.eq(symbol)))
      else:
        tape = self.tapes[row[1]]
        if op == "write": tape.write(Value.constant(row[2]), event)
        else:
          side = int(row[2] == -1)
          tape.move(row[2], event, slot=bases[row[1], side] + self.blocks["slots"][state])
        forward(row[3], event)
    halt = disjunction(*halts)
    self.circuit.require(disjunction(halt, *next_pc.values()), active)
    self.pc = Value.select(conjunction(active, neg(halt)), Value(next_pc), self.pc)
    self.done = disjunction(self.done, halt)

  def finalize(self):
    for tape in self.tapes: tape.finalize()
    self.circuit.put(self.pc_key, self.pc)
    self.circuit.put(self.done_key, Value.select(self.done, Value.constant(True), Value.constant(False)))
