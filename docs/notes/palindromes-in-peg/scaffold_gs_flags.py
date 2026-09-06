"""Finite palindrome-prefix flag worker on an actual frozen mirror view.

The fixture loads a binary word, | marks the lower interval endpoint, !
starts the offline worker, and dots execute local instructions. Upper is
the loaded word's length. The worker builds a persistent result stack.
"""
from gs_flag_heads import compile_flags, FLAG_HEADS
from gs_dual_flags import compile_dual_flags, DUAL_HEADS
from gs_heads import TESTS
from gs_head_liveness import analyze_readers
from scaffold_circuit import (Circuit, Value, Ref, NEW, PREVIOUS, TRUE, FALSE,
                              conjunction, disjunction, neg, choose)
from scaffold_circuit_structs import Counter, Stack, StackPool
from scaffold_live_distances import LiveDistances
from scaffold_stream_heads import StreamBank, MirrorHead, OrientedHead


class GSFlags:
  def __init__(self, circuit, prefix="flags", dual=False):
    self.circuit, self.prefix = circuit, prefix
    self.dual = dual
    self.program = compile_dual_flags() if dual else compile_flags()
    self.readers = analyze_readers(self.program)
    self.distances = LiveDistances(circuit, self.program, DUAL_HEADS if dual else FLAG_HEADS, prefix + ".distance")
    names = tuple(f"{prefix}.r{i}" for i in range(self.readers.registers))
    raw_names = names if dual else tuple(name + suffix for name in names for suffix in (".forward", ".reverse"))
    self.streams = StreamBank(circuit, (prefix + ".begin", prefix + ".end", *raw_names))
    self.begin = self.streams.heads[prefix + ".begin"]
    self.end = self.streams.heads[prefix + ".end"]
    self.registers = [(OrientedHead if dual else MirrorHead)(self.streams, name) for name in names]
    self.heads = {head: self.registers[color] for head, color in self.readers.colors.items()}
    self.pc_key, self.phase_key = prefix + ".pc", prefix + ".phase"
    self.pc = circuit.get(PREVIOUS, self.pc_key, tuple(range(len(self.program.code))), self.program.start)
    self.phase = circuit.get(PREVIOUS, self.phase_key, ("idle", "broadcast", "maintenance", "run", "done"), "idle")
    self.queue_key, self.remaining_key = prefix + ".queue", prefix + ".remaining"
    qnames = tuple(self.streams.queues.queues)
    self.queue = circuit.get(PREVIOUS, self.queue_key, qnames, qnames[0])
    self.remaining = circuit.get(PREVIOUS, self.remaining_key, (0, 1, 2, 3), 0)
    self.return_key = prefix + ".return"
    self.return_phase = circuit.get(PREVIOUS, self.return_key, ("idle", "run", "done"), "idle")
    self.post_input_key = prefix + ".post_input"
    self.post_input = circuit.get(PREVIOUS, self.post_input_key, ("idle", "run", "done"), "idle")
    self.cell_key = prefix + ".input_cell"
    self.cell = circuit.get_ref(PREVIOUS, self.cell_key)
    self.initialized_key = prefix + ".initialized"
    self.initialized = circuit.get(PREVIOUS, self.initialized_key, (False, True), False).eq(True)
    self.h = Counter(self.distances.pool, prefix + ".h", "cells")
    self.loading_counters = [self.h, self.distances.length]
    if not dual:
      self.n = Counter(self.distances.pool, prefix + ".n", "cells")
      self.b1 = Counter(self.distances.pool, prefix + ".b1", "cells")
      self.bh1 = Counter(self.distances.pool, prefix + ".bh1", "cells")
      self.loading_counters.extend((self.n, self.b1, self.bh1))
    self.flag_pool = StackPool(circuit, {"cells": 1}, (False, True))
    self.flags = Stack(self.flag_pool, prefix + ".output", "cells")
    self.emitted = FALSE

  def tick(self, symbols=None):
    c = self.circuit
    char = c.input() if symbols is None else symbols
    arrival = disjunction(char.eq("a"), char.eq("b"))
    mark, start, dot, reset = char.eq("|"), char.eq("!"), char.eq("."), char.eq("^")
    c.require(disjunction(*(self.phase.eq(name) for name in ("idle", "run", "done"))), disjunction(arrival, mark))
    c.require(disjunction(self.phase.eq("idle"), self.phase.eq("done")), start)
    phase = self.phase
    run, broadcast, maintenance = (conjunction(dot, phase.eq(name)) for name in ("run", "broadcast", "maintenance"))
    events = [conjunction(run, self.pc.eq(i)) for i in range(len(self.program.code))]
    next_pc, copies, moves, requests = {}, {}, {}, {}
    halt, flags = FALSE, {False: [], True: []}

    def edge(target, guard):
      next_pc[target] = disjunction(next_pc.get(target, FALSE), guard)

    def request(queue, guard):
      requests[queue] = disjunction(requests.get(queue, FALSE), guard)

    for state, (enabled, (event, targets)) in enumerate(zip(events, self.program.code)):
      op, *args = event
      if op in TESTS:
        if op == "symbols":
          a, b = (self.heads[name].read(enabled) for name in args)
          c.require(conjunction(neg(a.eq(None)), neg(b.eq(None))), enabled)
          decision = a.equal(b)
        else:
          decision = (self.distances.equal if op == "equal" else self.distances.less)(*args)
        edge(targets[0], conjunction(enabled, neg(decision)))
        edge(targets[1], conjunction(enabled, decision))
      elif op == "halt":
        halt = disjunction(halt, enabled)
      else:
        edge(targets[0], enabled)
        if op == "copy":
          target, source = args
          if target in self.readers.after[state]:
            pair = self.readers.colors[target], self.readers.colors[source]
            if pair[0] != pair[1]:
              copies[pair] = disjunction(copies.get(pair, FALSE), enabled)
        elif op == "move":
          head, direction = args[0][0]
          if head in self.readers.after[state]:
            key = self.readers.colors[head], direction
            moves[key] = disjunction(moves.get(key, FALSE), enabled)
        elif op == "flag":
          flags[args[0]].append(enabled)
        else:
          raise ValueError("unsupported GS flag instruction")
    self.distances.execute(events)
    for (register, direction), enabled in moves.items():
      queue, needed = self.registers[register].move(direction, enabled)
      request(queue, needed)
    for (target, source), enabled in copies.items():
      self.registers[target].copy_from(self.registers[source], enabled)
    self.flags.clear(start)
    for value, conditions in flags.items():
      self.flags.push(data=Value.constant(value), enabled=disjunction(*conditions), slot=0)
    self.emitted = disjunction(*(condition for conditions in flags.values() for condition in conditions))

    self.begin.queue.push(self.cell, broadcast)
    request(self.begin.queue.name, broadcast)
    for name, queue in self.streams.queues.queues.items():
      queue.work_unit(conjunction(maintenance, self.queue.eq(name)))
    self.end.follow_arrival(NEW, arrival)
    self.cell = Ref.select(arrival, NEW, self.cell)
    self.post_input = Value.select(arrival, Value({name: phase.eq(name) for name in ("idle", "run", "done")}), self.post_input)
    # Entry distances are built by unit updates while loading; no length
    # or interval integer is injected when starting a job.
    if not self.dual:
      initial = neg(self.initialized)
      self.n.inc(initial, slot=0)
      self.n.inc(arrival, slot=1)
      self.n.inc(arrival, slot=2)
      self.b1.inc(initial, slot=3)
      self.b1.inc(arrival, slot=4)
      self.bh1.inc(initial, slot=5)
      self.bh1.inc(arrival, slot=6)
      self.bh1.inc(arrival, slot=7)
    self.h.inc(arrival, slot=8)
    self.distances.load_one(arrival)
    self.h.reset(mark)
    if not self.dual:
      self.bh1.copy_from(self.b1, mark)
    self.initialized = TRUE

    if self.dual:
      self.heads["Origin"].start(self.begin, False, start)
      self.heads["TextOrigin"].start(self.end, True, start)
      self.heads["OriginalEnd"].start(self.begin, True, start)
      entry = {("Lower", "Upper"): (self.h, -1),
               ("Origin", "OriginalEnd"): (self.distances.length, -1),
               ("Origin", "Upper"): (self.distances.length, -1),
               ("OriginalEnd", "Lower"): (self.h, 1),
               ("OriginalEnd", "TextOrigin"): (self.distances.length, 1),
               ("OriginalEnd", "Upper"): None}
    else:
      self.heads["Origin"].start(self.begin, self.end, False, start)
      self.heads["OriginalEnd"].start(self.begin, self.end, True, start)
      entry = {("Origin", "OriginalEnd"): (self.n, -1),
                               ("OriginalEnd", "Lower"): (self.bh1, 1),
                               ("Origin", "Upper"): (self.distances.length, -1),
                               ("OriginalEnd", "Upper"): (self.b1, 1),
                               ("Lower", "Upper"): (self.h, -1)}
    self.distances.initialize(entry, start)
    self.pc = Value.select(conjunction(run, neg(halt)), Value(next_pc), self.pc)
    self.pc = Value.select(start, Value.constant(self.program.start), self.pc)
    finished_work = conjunction(maintenance, self.remaining.eq(1))
    self.remaining = Value.select(maintenance, self.remaining.map(lambda n: max(0, n - 1)), self.remaining)
    self.phase = Value.select(finished_work, self.return_phase, self.phase)
    needed = disjunction(*requests.values())
    self.queue = Value.select(needed, Value(requests), self.queue)
    self.remaining = Value.select(needed, Value.constant(3), self.remaining)
    self.return_phase = Value.select(needed, Value.select(broadcast, self.post_input, Value.constant("run")), self.return_phase)
    self.phase = Value.select(needed, Value.constant("maintenance"), self.phase)
    self.phase = Value.select(arrival, Value.constant("broadcast"), self.phase)
    self.phase = Value.select(start, Value.constant("run"), self.phase)
    self.phase = Value.select(halt, Value.constant("done"), self.phase)
    self.begin.reset(reset)
    self.end.reset(reset)
    for counter in self.loading_counters:
      counter.reset(reset)
    self.flags.clear(reset)
    self.phase = Value.select(reset, Value.constant("idle"), self.phase)
    self.initialized = conjunction(self.initialized, neg(reset))

  def finalize(self):
    self.distances.finalize()
    for counter in self.loading_counters:
      if counter is not self.distances.length:
        counter.finalize()
    self.streams.finalize()
    for head in self.registers:
      head.finalize()
    self.flags.finalize()
    for key, value in ((self.pc_key, self.pc), (self.phase_key, self.phase),
                       (self.queue_key, self.queue), (self.remaining_key, self.remaining),
                       (self.return_key, self.return_phase), (self.post_input_key, self.post_input)):
      self.circuit.put(key, value)
    self.circuit.put(self.initialized_key, Value.select(self.initialized, Value.constant(True), Value.constant(False)))
    self.circuit.put_ref(self.cell_key, self.cell)
    self.circuit.put(self.prefix + ".emitted", Value.select(self.emitted, Value.constant(True), Value.constant(False)),
                     (False, True), False)


def fixture(dual=False):
  circuit = Circuit("ab|!.^")
  circuit.put("input", Value.select(circuit.input().eq("b"), Value.constant("b"), Value.constant("a")), tuple("ab"), "a")
  worker = GSFlags(circuit, dual=dual)
  worker.tick()
  worker.finalize()
  _, answer = worker.flags.peek()
  machine = circuit.machine(conjunction(worker.phase.eq("done"), neg(worker.flags.empty()), answer.eq(True)))
  return circuit, worker, machine
