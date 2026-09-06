"""Run a finite local-tape program on bounded-neighbourhood scaffold cells.

Each invocation of step executes one actual finite instruction. It does not
make an offline program real time. Tape addresses never enter machine labels;
only finite tape symbols and a program counter from the supplied finite table
do. The independent VM enforces backward/self pointer reachability.
"""
from scavm import SELF
from scavm_structs import StackView
from fpp_finite import BLANK


class TapeView:
  def __init__(self, vm, previous, builder, name):
    self.vm, self.builder, self.name = vm, builder, name
    self.focus = BLANK if previous is None else vm.label(previous)[name + ".symbol"]
    self.left = StackView(vm, previous, builder, name + ".l")
    self.right = StackView(vm, previous, builder, name + ".r")

  def read(self):
    return self.focus

  def write(self, symbol):
    self.focus = symbol

  def reset(self):
    """Drop this private persistent tape in bounded work, including its origin."""
    self.focus = BLANK
    self.left.top = self.right.top = None

  def move(self, direction):
    if direction not in (-1, 1):
      raise ValueError("unit tape move required")
    if direction == -1 and self.left.empty():
      raise ValueError("left-end crossing")
    pushed, popped = (self.left, self.right) if direction == 1 else (self.right, self.left)
    slot = self.builder.slot(self.name + ".v")
    self.builder.label[f"{self.name}.v{slot}"] = self.focus
    pushed.push(SELF, slot)
    if popped.empty():
      self.focus = BLANK
    else:
      node, slot = popped.pop2()
      label = self.builder.label if node is SELF else self.vm.label(node)
      self.focus = label[f"{self.name}.v{slot}"]

  def finalize(self):
    self.left.finalize()
    self.right.finalize()
    self.builder.label[self.name + ".symbol"] = self.focus


class ProgramView:
  def __init__(self, vm, previous, builder, program, name=""):
    self.vm, self.builder, self.program = vm, builder, program
    self.prefix = name + "." if name else ""
    self.tapes = [TapeView(vm, previous, builder, f"{self.prefix}t{i}") for i in range(program.ntapes)]
    if previous is None:
      self.pc, self.done = program.start, True
    else:
      label = vm.label(previous)
      self.pc, self.done = int(label[self.prefix + "pc"]), label[self.prefix + "halted"]

  def reset(self):
    # The finite table fixes the number of tapes. No scan or mutation of
    # retained nodes is needed in the persistent scaffold representation.
    for tape in self.tapes:
      tape.reset()
    self.pc, self.done = self.program.start, True

  def start(self, entry=None):
    self.pc = self.program.start if entry is None else entry
    self.done = False

  def step(self):
    if self.done:
      raise ValueError("program is not running")
    row = self.program.code[self.pc]
    op = row[0]
    if op == "halt":
      self.done = True
    elif op == "read":
      self.pc = row[2][self.tapes[row[1]].read()]
    elif op in ("write", "move"):
      getattr(self.tapes[row[1]], op)(row[2])
      self.pc = row[3]
    else:
      raise ValueError("observer-only instruction is not a scaffold operation")

  def finalize(self):
    for tape in self.tapes:
      tape.finalize()
    self.builder.label[self.prefix + "pc"] = str(self.pc)
    self.builder.label[self.prefix + "halted"] = self.done

  def step_read_block(self):
    """Execute one read plus its finite following write/move block.

    Callers must use a table accepted by read_blocks.analyze. The loop bound
    is the fixed table size, never a tape value or an input-dependent length.
    """
    ProgramView.step(self)
    for _ in range(len(self.program.code)):
      if self.done or self.program.code[self.pc][0] in ("read", "halt"):
        return
      ProgramView.step(self)
    raise ValueError("non-reading instruction cycle")
