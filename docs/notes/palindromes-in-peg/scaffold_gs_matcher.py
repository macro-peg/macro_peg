"""Full finite GS matcher with real input queues and frozen prefix heads.

Standalone protocol: load binary letters (draining the bounded broadcast
after each), ! freezes the reversed prefix, then append text letters and
service local instructions with dots. This is a compiler/interfacing test;
the final PAL controller must schedule these internal operations itself.
"""
from gs_match_heads import compile_matcher, MATCH_HEADS, MATCH_TESTS
from gs_head_liveness import analyze_readers
from scaffold_circuit import (Circuit, Value, Ref, NEW, PREVIOUS, TRUE, FALSE,
                              conjunction, disjunction, neg, choose)
from scaffold_live_distances import LiveDistances
from scaffold_stream_heads import StreamBank, PatternTextHead


class GSMatcher:
  def __init__(self, circuit, prefix="matcher"):
    self.circuit, self.prefix = circuit, prefix
    self.program = compile_matcher()
    self.readers = analyze_readers(self.program)
    self.distances = LiveDistances(circuit, self.program, MATCH_HEADS, prefix + ".distance",
                                  observe_positions=False, availability_distance=False)
    names = tuple(f"{prefix}.r{i}" for i in range(self.readers.registers))
    self.streams = StreamBank(circuit, (prefix + ".begin", prefix + ".end",
                                      *(name + suffix for name in names for suffix in (".pattern", ".text"))))
    self.begin = self.streams.heads[prefix + ".begin"]
    self.end = self.streams.heads[prefix + ".end"]
    self.registers = [PatternTextHead(self.streams, name) for name in names]
    self.heads = {head: self.registers[color] for head, color in self.readers.colors.items()}
    self.pc_key, self.phase_key = prefix + ".pc", prefix + ".phase"
    self.pc = circuit.get(PREVIOUS, self.pc_key, tuple(range(len(self.program.code))), self.program.start)
    self.phase = circuit.get(PREVIOUS, self.phase_key, ("idle", "broadcast", "maintenance", "run"), "idle")
    self.active_key = prefix + ".active"
    self.active = circuit.get(PREVIOUS, self.active_key, (False, True), False).eq(True)
    self.queue_key, self.remaining_key = prefix + ".queue", prefix + ".remaining"
    qnames = tuple(self.streams.queues.queues)
    self.queue = circuit.get(PREVIOUS, self.queue_key, qnames, qnames[0])
    self.remaining = circuit.get(PREVIOUS, self.remaining_key, (0, 1, 2, 3), 0)
    self.return_key, self.index_key = prefix + ".return", prefix + ".broadcast_index"
    self.return_phase = circuit.get(PREVIOUS, self.return_key, ("broadcast", "run"), "broadcast")
    self.broadcast_queues = (self.begin.queue.name, *(head.text.queue.name for head in self.registers))
    self.broadcast_index = circuit.get(PREVIOUS, self.index_key, tuple(range(len(self.broadcast_queues) + 1)), 0)
    self.cell_key, self.output_key = prefix + ".input_cell", prefix + ".matched"
    self.cell = circuit.get_ref(PREVIOUS, self.cell_key)
    self.output = circuit.get(PREVIOUS, self.output_key, (False, True), False).eq(True)

  def tick(self, symbols=None):
    c = self.circuit
    char = c.input() if symbols is None else symbols
    arrival = disjunction(char.eq("a"), char.eq("b"))
    start = char.eq("!")
    dot = char.eq(".")
    c.require(disjunction(self.phase.eq("idle"), self.phase.eq("run")), disjunction(arrival, start))
    c.require(self.end.focus.present(), start)
    phase = self.phase
    run = conjunction(dot, phase.eq("run"))
    broadcast = conjunction(dot, phase.eq("broadcast"))
    maintenance = conjunction(dot, phase.eq("maintenance"))
    events = [conjunction(run, self.pc.eq(i)) for i in range(len(self.program.code))]
    next_pc, copies, moves, requests = {}, {}, {}, {}
    matched = FALSE

    def edge(target, guard):
      next_pc[target] = disjunction(next_pc.get(target, FALSE), guard)

    def request(queue, guard):
      requests[queue] = disjunction(requests.get(queue, FALSE), guard)

    for state, (enabled, (event, targets)) in enumerate(zip(events, self.program.code)):
      op, *args = event
      if op in MATCH_TESTS:
        if op == "available":
          decision = self.heads[args[0]].available()
        elif op == "symbols":
          a, b = (self.heads[name].read(enabled) for name in args)
          c.require(conjunction(neg(a.eq(None)), neg(b.eq(None))), enabled)
          decision = a.equal(b)
        else:
          decision = (self.distances.equal if op == "equal" else self.distances.less)(*args)
        edge(targets[0], conjunction(enabled, neg(decision)))
        edge(targets[1], conjunction(enabled, decision))
      elif op == "halt":
        c.require(FALSE, enabled)
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
        elif op == "assert_equal":
          c.require(self.distances.equal(*args), enabled)
        elif op == "match":
          # A positive result is for this arrival exactly. A queued older
          # match must not silently become the next prefix's answer.
          c.require(neg(self.heads["B"].available()), enabled)
          matched = disjunction(matched, enabled)
        else:
          raise ValueError("unsupported GS matcher instruction")
    self.distances.execute(events)
    for (register, direction), enabled in moves.items():
      queue, needed = self.registers[register].move(direction, enabled)
      request(queue, needed)
    for (target, source), enabled in copies.items():
      self.registers[target].copy_from(self.registers[source], enabled)

    # One broadcast queue is updated in this instruction. The explicit
    # maintenance state services its three work units before proceeding.
    count = len(self.broadcast_queues)
    finished = choose(self.active, self.broadcast_index.eq(count), self.broadcast_index.eq(1))
    push = conjunction(broadcast, neg(finished))
    for index, name in enumerate(self.broadcast_queues):
      enabled = conjunction(push, self.broadcast_index.eq(index))
      self.streams.queues.queues[name].push(self.cell, enabled)
      request(name, enabled)
    for name, queue in self.streams.queues.queues.items():
      queue.work_unit(conjunction(maintenance, self.queue.eq(name)))
    self.end.follow_arrival(NEW, arrival)
    self.distances.load_one(arrival)
    self.cell = Ref.select(arrival, NEW, self.cell)

    self.heads["Origin"].start(self.end, start)
    self.heads["Tail"].start(self.begin, start)
    self.distances.initialize({("Origin", "Tail"): (self.distances.length, -1)}, start)
    self.pc = Value.select(run, Value(next_pc), self.pc)
    self.pc = Value.select(start, Value.constant(self.program.start), self.pc)
    self.active = disjunction(self.active, start)
    self.output = disjunction(conjunction(self.output, neg(disjunction(arrival, start))), matched)
    self.broadcast_index = Value.select(push, self.broadcast_index.map(lambda n: min(n + 1, count)), self.broadcast_index)
    self.broadcast_index = Value.select(arrival, Value.constant(0), self.broadcast_index)

    finished_work = conjunction(maintenance, self.remaining.eq(1))
    self.remaining = Value.select(maintenance, self.remaining.map(lambda n: max(0, n - 1)), self.remaining)
    self.phase = Value.select(finished_work, self.return_phase, self.phase)
    self.phase = Value.select(conjunction(broadcast, finished),
                             Value.select(self.active, Value.constant("run"), Value.constant("idle")), self.phase)
    needed = disjunction(*requests.values())
    self.queue = Value.select(needed, Value(requests), self.queue)
    self.remaining = Value.select(needed, Value.constant(3), self.remaining)
    self.return_phase = Value.select(needed, Value.select(push, Value.constant("broadcast"), Value.constant("run")), self.return_phase)
    self.phase = Value.select(needed, Value.constant("maintenance"), self.phase)
    self.phase = Value.select(arrival, Value.constant("broadcast"), self.phase)
    self.phase = Value.select(start, Value.constant("run"), self.phase)

  def finalize(self):
    self.distances.finalize()
    self.streams.finalize()
    for head in self.registers:
      head.finalize()
    for key, value in ((self.pc_key, self.pc), (self.phase_key, self.phase),
                       (self.queue_key, self.queue), (self.remaining_key, self.remaining),
                       (self.return_key, self.return_phase), (self.index_key, self.broadcast_index)):
      self.circuit.put(key, value)
    for key, value in ((self.active_key, self.active), (self.output_key, self.output)):
      self.circuit.put(key, Value.select(value, Value.constant(True), Value.constant(False)))
    self.circuit.put_ref(self.cell_key, self.cell)


def fixture():
  circuit = Circuit("ab!.")
  circuit.put("input", Value.select(circuit.input().eq("b"), Value.constant("b"), Value.constant("a")), tuple("ab"), "a")
  worker = GSMatcher(circuit)
  worker.tick()
  worker.finalize()
  return circuit, worker, circuit.machine(worker.output)
