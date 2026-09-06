"""Finite GS head table -> one-instruction scaffold transitions.

The standalone fixture consumes a/b/# to load a snapshot, ! to finish it,
and . for local work. It is an instruction/compiler test, NOT a PAL grammar.
Snapshot loading and rewinding are real transitions, never injected pointers.
"""
from gs_heads import HEADS, BLIND, TESTS, compile_controller, unit_moves
from scaffold_circuit import (Circuit, Value, Ref, PREVIOUS, TRUE, FALSE,
                              conjunction, disjunction, neg, choose)
from scaffold_circuit_structs import Tape, StackPool
from scaffold_head_distances import HeadDistances
from scaffold_live_distances import LiveDistances


class GSHeads:
  def __init__(self, circuit, program=None, prefix="gs", compact=True):
    self.circuit = circuit
    self.program = unit_moves(compile_controller() if program is None else program)
    self.compact = compact
    self.bank = LiveDistances(circuit, self.program, HEADS, prefix=prefix + ".distance") if compact else \
                HeadDistances(circuit, HEADS, prefix=prefix + ".distance", exclusive_moves=True)
    self.pool = StackPool(circuit, {"cells": 1}, ("_", "a", "b", "#"))
    self.tapes = {head: Tape(circuit, prefix + "." + head, self.pool.payload, pool=self.pool)
                  for head in HEADS if head not in BLIND}
    self.pc_key, self.mode_key = prefix + ".pc", prefix + ".mode"
    self.pc = circuit.get(PREVIOUS, self.pc_key, tuple(range(len(self.program.code))), self.program.start)
    self.mode = circuit.get(PREVIOUS, self.mode_key, ("load", "rewind", "run", "done"), "load")
    self.found_key = prefix + ".found"
    self.found = circuit.get(PREVIOUS, self.found_key, (False, True), False).eq(True)
    self.border = FALSE

  def copy(self, target, source, enabled):
    if not self.compact:
      self.bank.copy(target, source, enabled)
    if target in self.tapes:
      if source not in self.tapes:
        raise ValueError("a data head cannot copy a blind coordinate")
      a, b = self.tapes[target], self.tapes[source]
      a.left.copy_from(b.left, enabled)
      a.right.copy_from(b.right, enabled)
      a.write(b.focus, enabled)

  def tick(self):
    circuit, char = self.circuit, self.circuit.input()
    load = conjunction(self.mode.eq("load"), disjunction(*(char.eq(s) for s in "ab#")))
    finish_load = conjunction(self.mode.eq("load"), char.eq("!"))
    rewind = conjunction(self.mode.eq("rewind"), char.eq("."))
    at_origin = self.tapes["Origin"].left.empty() if self.compact else self.bank.equal("Origin", "First")
    begin = conjunction(rewind, at_origin)
    active = conjunction(self.mode.eq("run"), char.eq("."))
    moves = {(head, d): [] for head in HEADS for d in (-1, 1)}
    copies, next_pc, halts, borders = {}, {}, [], []

    def edge(destination, enabled):
      next_pc[destination] = disjunction(next_pc.get(destination, FALSE), enabled)

    events = [conjunction(active, self.pc.eq(state)) for state in range(len(self.program.code))]
    for enabled, (event, targets) in zip(events, self.program.code):
      op, *args = event
      if op in TESTS:
        left, right = args
        if op == "symbols":
          a, b = self.tapes[left].focus, self.tapes[right].focus
          circuit.require(conjunction(neg(a.eq("_")), neg(b.eq("_"))), enabled)
          decision = a.equal(b)
        else:
          decision = (self.bank.equal if op == "equal" else self.bank.less)(left, right)
        edge(targets[0], conjunction(enabled, neg(decision)))
        edge(targets[1], conjunction(enabled, decision))
      elif op == "halt":
        halts.append(enabled)
      else:
        edge(targets[0], enabled)
        if op == "move":
          if len(args[0]) != 1 or abs(args[0][0][1]) != 1:
            raise ValueError("unit head instruction required")
          moves[args[0][0]].append(enabled)
        elif op == "copy":
          pair = tuple(args)
          copies[pair] = disjunction(copies.get(pair, FALSE), enabled)
        elif op == "border":
          borders.append(enabled)
        else:
          raise ValueError("unsupported GS instruction")

    # Input loading, rewind, and execution are disjoint modes. Therefore at
    # most one of these head movements allocates cells in this physical step.
    moves["OriginalEnd", 1].append(load)
    moves["Origin", -1].append(conjunction(rewind, neg(at_origin)))
    self.tapes["OriginalEnd"].write(Value({s: char.eq(s) for s in "ab#"}), load)
    if self.compact:
      self.bank.execute(events)
      self.bank.load_one(load)
    for (head, direction), conditions in moves.items():
      enabled = disjunction(*conditions)
      if enabled == FALSE:
        continue
      if not self.compact:
        self.bank.move(head, direction, enabled)
      if head in self.tapes:
        self.tapes[head].move(direction, enabled, slot=0)
    for (target, source), enabled in copies.items():
      self.copy(target, source, enabled)
    self.copy("Origin", "OriginalEnd", finish_load)
    for target in HEADS:
      if target not in ("Origin", "OriginalEnd"):
        self.copy(target, "Origin", begin)
    if self.compact:
      self.bank.initialize({("Origin", "OriginalEnd"): (self.bank.length, -1)}, begin)
    halt = disjunction(*halts)
    self.border = disjunction(*borders)
    self.found = disjunction(self.found, self.border)
    self.pc = Value.select(conjunction(active, neg(halt)), Value(next_pc), self.pc)
    self.mode = Value.select(finish_load, Value.constant("rewind"), self.mode)
    self.mode = Value.select(begin, Value.constant("run"), self.mode)
    self.mode = Value.select(halt, Value.constant("done"), self.mode)

  def finalize(self):
    self.bank.finalize()
    for tape in self.tapes.values():
      tape.finalize()
    self.circuit.put(self.pc_key, self.pc)
    self.circuit.put(self.mode_key, self.mode)
    self.circuit.put(self.found_key, Value.select(self.found, Value.constant(True), Value.constant(False)))


def fixture(program=None, compact=True):
  circuit = Circuit("ab#!.")
  worker = GSHeads(circuit, program, compact=compact)
  worker.tick()
  worker.finalize()
  circuit.put("gs.border", Value.select(worker.border, Value.constant(True), Value.constant(False)),
              (False, True), False)
  machine = circuit.machine(conjunction(worker.mode.eq("done"), worker.found))
  return circuit, worker, machine
