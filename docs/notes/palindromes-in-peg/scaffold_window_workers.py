"""GS matcher and proper-prefix flags on the original input-node stream.

Every actual input character advances the global raw end once. Frozen views
are cursor snapshots into that stream: reverse views read just left of their
boundary cursor. They do not copy, pad, or append synthetic input symbols.
"""
from copy import copy

from gs_match_heads import compile_matcher, MATCH_HEADS
from gs_dual_flags import compile_dual_flags, DUAL_HEADS
from gs_head_liveness import analyze_readers
from scaffold_circuit import (Value, PREVIOUS, TRUE, FALSE, conjunction as AND,
                              disjunction as OR, neg, choose)
from scaffold_rom import controller_table
from scaffold_window_counter import Bits
from scaffold_window_live import WindowLiveDistances
from scaffold_window_stream import WindowStream
from scaffold_flag_packets import FlagPackets, FlagStack, PacketWriter
from symbolic_sca2peg import clear_expression_cache


class WindowWorker:
  def __init__(self, circuit, prefix, quantum, program, names, *, flags=False):
    if type(quantum) is not int or quantum < 1:
      raise ValueError("a positive finite service quantum is required")
    self.circuit, self.prefix, self.quantum, self.program = circuit, prefix, quantum, program
    self.is_flags = flags
    self.readers = analyze_readers(program)
    self.reader_names = tuple(f"{prefix}.data{i}" for i in range(self.readers.registers))
    movement = max((abs(delta) for event, _ in program.code if event[0] == "move"
                    for _, delta in event[1]), default=1)
    self.h_key = prefix + ".h"
    self.distances = WindowLiveDistances(circuit, program, names,
      1 + 2 * quantum * movement, prefix + ".distance", extra=(self.h_key,) if flags else (),
      availability_distance=False)
    self.table = controller_table(program, names, self.readers, self.reader_names,
                                  batched=True, augment=self.distances.augment_rows)
    self.stream = WindowStream(circuit, (prefix + ".begin", prefix + ".end", *self.reader_names),
                               3 + quantum * movement, prefix + ".stream")
    self.begin, self.end = (self.stream.heads[prefix + name] for name in (".begin", ".end"))
    self.data = {name: self.stream.heads[name] for name in self.reader_names}
    self.reverse = {name: circuit.get(PREVIOUS, name + ".reverse", (False, True), False).eq(True)
                    for name in self.reader_names}
    self.pc_key, self.mode_key = prefix + ".pc", prefix + ".mode"
    self.pc = circuit.get(PREVIOUS, self.pc_key, tuple(range(len(program.code))), program.start)
    self.mode = circuit.get(PREVIOUS, self.mode_key, ("idle", "run", "done"), "idle")
    self.output = FALSE
    if flags:
      self.flag_pool = FlagPackets(circuit, quantum, prefix + ".packets")
      self.flags = FlagStack(self.flag_pool, prefix + ".output")
    self.writer = None

  def arrive(self):
    self.end.move(1)
    self.distances.load_one(TRUE)
    if self.is_flags:
      self.distances.values.add(self.h_key, 1)

  def raw_start(self, head, source, reverse, enabled):
    name = self.reader_names[self.readers.colors[head]]
    self.data[name].copy_from(source, enabled)
    self.reverse[name] = choose(enabled, TRUE if reverse else FALSE, self.reverse[name])

  def reset_flags(self, enabled):
    if not self.is_flags: raise ValueError("only flag workers have a segment builder")
    self.begin.copy_from(self.end, enabled)
    self.distances.values.reset(self.distances.length_key, enabled)
    self.distances.values.reset(self.h_key, enabled)
    self.flags.clear(enabled)
    self.mode = Value.select(enabled, Value.constant("idle"), self.mode)

  def start(self, enabled):
    c, d = self.circuit, self.distances
    if self.is_flags:
      c.require(neg(self.mode.eq("run")), enabled)
      self.raw_start("Origin", self.begin, False, enabled)
      self.raw_start("TextOrigin", self.end, True, enabled)
      self.raw_start("OriginalEnd", self.begin, True, enabled)
      d.initialize_values({("Lower", "Upper"): (self.h_key, -1),
        ("Origin", "OriginalEnd"): (d.length_key, -1),
        ("Origin", "Upper"): (d.length_key, -1),
        ("OriginalEnd", "Lower"): (self.h_key, 1),
        ("OriginalEnd", "TextOrigin"): (d.length_key, 1),
        ("OriginalEnd", "Upper"): None}, enabled)
      self.flags.clear(enabled)
    else:
      self.raw_start("Origin", self.end, True, enabled)
      self.raw_start("Tail", self.end, False, enabled)
      d.initialize_values({("Origin", "Tail"): (d.length_key, -1)}, enabled)
    self.pc = Value.select(enabled, Value.constant(self.program.start), self.pc)
    self.mode = Value.select(enabled, Value.constant("run"), self.mode)

  def mark(self, enabled):
    self.distances.values.reset(self.h_key, enabled)

  def orientation(self, which):
    return OR(*(AND(which.eq(name), value) for name, value in self.reverse.items()))

  def read(self, which, enabled):
    head = self.stream.select_head(which)
    # The movement radius reserves a further unit for reverse reads.
    head.offset = Bits.select(self.orientation(which), head.offset.add(-1), head.offset)
    return head.read(enabled)

  def service(self):
    if self.is_flags:
      self.writer = PacketWriter(self.flags)
    for _ in range(self.quantum):
      self.step(self.mode.eq("run"))
      clear_expression_cache()

  def step(self, active):
    c, fields = self.circuit, self.table.read(self.pc.bits)
    opcode = fields["op"]
    equal, less = self.distances.compare(fields)
    symbol_test = AND(active, opcode.eq("symbols"))
    a, b = (self.read(fields[key], symbol_test) for key in ("data_left", "data_right"))
    available = self.stream.select_head(fields["data_left"]).available()
    decision = choose(opcode.eq("equal"), equal, choose(opcode.eq("less"), less,
               choose(opcode.eq("available"), available, a.equal(b))))
    c.require(equal, AND(active, opcode.eq("assert_equal")))
    if not self.is_flags:
      matched = AND(active, opcode.eq("match"))
      c.require(neg(self.data[self.reader_names[self.readers.colors["B"]]].available()), matched)
      self.output = OR(self.output, matched)
    self.distances.execute(fields, active)
    source = self.stream.select_head(fields["data_source"])
    reverse_source = self.orientation(fields["data_source"])
    for name, head in self.data.items():
      delta = fields["data_delta." + name]
      delta = Value.select(self.reverse[name], delta.map(lambda amount: -amount), delta)
      head.move_selected(delta, active)
      copying = AND(active, opcode.eq("copy"), fields["data_target"].eq(name))
      head.copy_from(source, copying)
      self.reverse[name] = choose(copying, reverse_source, self.reverse[name])
    if self.is_flags:
      self.writer.push(fields["bit"].eq(True), AND(active, opcode.eq("flag")))
      self.mode = Value.select(AND(active, opcode.eq("halt")), Value.constant("done"), self.mode)
    else:
      c.require(FALSE, AND(active, opcode.eq("halt")))
    self.pc = Value.select(active, Value.select(decision, fields["yes"], fields["no"]), self.pc)

  def finalize(self):
    self.distances.finalize()
    self.stream.finalize()
    self.circuit.put(self.pc_key, self.pc)
    self.circuit.put(self.mode_key, self.mode)
    for name, bit in self.reverse.items():
      self.circuit.put(name + ".reverse", Value.select(bit, Value.constant(True), Value.constant(False)))
    if self.is_flags:
      self.flags.finalize()
    self.circuit.put(self.prefix + ".matched", Value.select(self.output, Value.constant(True), Value.constant(False)),
                     (False, True), False)


def matcher(circuit, prefix="matcher", quantum=512):
  return WindowWorker(circuit, prefix, quantum, compile_matcher(unit=False), MATCH_HEADS)


def flags(circuit, prefix="flags", quantum=1024):
  return WindowWorker(circuit, prefix, quantum, compile_dual_flags(unit=False), DUAL_HEADS, flags=True)
