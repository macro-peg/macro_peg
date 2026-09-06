"""A fixed burst of actual GS instructions in one ordinary input transition.

Protocol: a/b/# load the word, ! freezes it, and each . performs `quantum`
instructions. Windowed positions and block cursors avoid per-instruction
heap cells and phase-address dispatch. This is a compiler integration test,
not the final PAL grammar; its input still includes the work protocol.
"""
from gs_heads import HEADS, compile_controller, unit_moves
from gs_head_liveness import analyze_readers
from scaffold_circuit import (Circuit, Value, PREVIOUS, FALSE, conjunction as AND,
                              disjunction as OR, neg, choose)
from scaffold_rom import controller_table
from scaffold_window_positions import WindowPositions
from scaffold_window_stream import WindowStream


class WindowGS:
  def __init__(self, circuit, quantum=4, prefix="gs", program=None):
    if type(quantum) is not int or quantum < 1:
      raise ValueError("a positive fixed instruction quantum is required")
    self.circuit, self.quantum, self.prefix = circuit, quantum, prefix
    self.program = unit_moves(compile_controller()) if program is None else program
    self.readers = analyze_readers(self.program)
    self.reader_names = tuple(f"{prefix}.data{i}" for i in range(self.readers.registers))
    self.table = controller_table(self.program, HEADS, self.readers, self.reader_names)
    self.positions = WindowPositions(circuit, HEADS, 2 * quantum + 4, prefix + ".positions")
    self.stream = WindowStream(circuit, (prefix + ".begin", prefix + ".end", *self.reader_names),
                               2 * quantum + 4, prefix + ".stream")
    self.begin, self.end = (self.stream.heads[prefix + name] for name in (".begin", ".end"))
    self.data = {name: self.stream.heads[name] for name in self.reader_names}
    self.pc_key, self.mode_key, self.found_key = prefix + ".pc", prefix + ".mode", prefix + ".found"
    self.pc = circuit.get(PREVIOUS, self.pc_key, tuple(range(len(self.program.code))), self.program.start)
    self.mode = circuit.get(PREVIOUS, self.mode_key, ("load", "run", "done"), "load")
    self.found = circuit.get(PREVIOUS, self.found_key, (False, True), False).eq(True)

  def run_round(self):
    c, char = self.circuit, self.circuit.input()
    loading = AND(self.mode.eq("load"), OR(*(char.eq(symbol) for symbol in "ab#")))
    start = AND(self.mode.eq("load"), char.eq("!"))
    c.require(OR(loading, start, AND(neg(self.mode.eq("load")), char.eq("."))))
    self.positions.move("OriginalEnd", 1, loading)
    self.end.move(1, loading)
    for head in self.readers.before[self.program.start]:
      source = self.end if head == "OriginalEnd" else self.begin
      self.data[self.reader_names[self.readers.colors[head]]].copy_from(source, start)
    self.mode = Value.select(start, Value.constant("run"), self.mode)
    for _ in range(self.quantum):
      self.step(AND(char.eq("."), self.mode.eq("run")))

  def step(self, active):
    c, fields = self.circuit, self.table.read(self.pc.bits)
    opcode = fields["op"]
    left, right = (self.positions.select_position(fields[key]) for key in ("left", "right"))
    equal, less = self.positions.compare_positions(left, right)
    symbol_test = AND(active, opcode.eq("symbols"))
    a, b = (self.stream.select_head(fields[key]).read(symbol_test) for key in ("data_left", "data_right"))
    decision = choose(opcode.eq("equal"), equal,
                       choose(opcode.eq("less"), less, a.equal(b)))
    moving, copying = AND(active, opcode.eq("move")), AND(active, opcode.eq("copy"))
    forward, backward = fields["direction"].eq(1), fields["direction"].eq(-1)
    for name in HEADS:
      target = fields["left"].eq(name)
      self.positions.move(name, 1, AND(moving, target, forward))
      self.positions.move(name, -1, AND(moving, target, backward))
      self.positions.copy_position(name, right, AND(copying, target))
    source = self.stream.select_head(fields["data_source"])
    for name, head in self.data.items():
      target = fields["data_move"].eq(name)
      head.move(1, AND(moving, target, forward))
      head.move(-1, AND(moving, target, backward))
      head.copy_from(source, AND(copying, fields["data_target"].eq(name)))
    self.pc = Value.select(active, Value.select(decision, fields["yes"], fields["no"]), self.pc)
    self.found = OR(self.found, AND(active, opcode.eq("border")))
    self.mode = Value.select(AND(active, opcode.eq("halt")), Value.constant("done"), self.mode)

  def finalize(self):
    self.positions.finalize()
    self.stream.finalize()
    self.circuit.put(self.pc_key, self.pc)
    self.circuit.put(self.mode_key, self.mode)
    self.circuit.put(self.found_key, Value.select(self.found, Value.constant(True), Value.constant(False)))


def fixture(quantum=4, program=None):
  circuit = Circuit("ab#!.")
  worker = WindowGS(circuit, quantum, program=program)
  worker.run_round()
  worker.finalize()
  return circuit, worker, circuit.machine(AND(worker.mode.eq("done"), worker.found))
