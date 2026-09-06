"""Clonable readonly online input heads on the bounded-neighbourhood scaffold.

Each head has persistent left/right stacks and a realtime incoming queue.
Append each arriving input cell to every head once. A head may move left,
move right if input is available, or copy another head's complete state.
Broadcast each arrival to all heads before client operations. Copies must
use views in the same VM/builder with the same current arrival phase.
No addresses, lengths, timestamps or equality of node pointers are tested.
The only pointer distinctions are empty None and the current builder SELF.

Calls per scaffold step must be bounded by the outer finite controller.
Input cells carry their character in label['input']; origin is None.
This is a component experiment, not a complete PAL SCA or PEG emitter.
"""
from scavm import SELF
from scavm_structs import StackView, RTQueueView


class InputHead:
  def __init__(self, vm, previous, builder, name):
    self.vm, self.builder, self.name = vm, builder, name
    self.arrival_received = False
    self.focus = None if previous is None else vm.get(previous, name + ".focus")
    self.left_stack = StackView(vm, previous, builder, name + ".l")
    self.right_stack = StackView(vm, previous, builder, name + ".r")
    self.incoming = RTQueueView(vm, previous, builder, name + ".in")

  def append(self, cell):
    if self.arrival_received:
      raise ValueError("arrival already received in this step")
    self.incoming.push(cell)
    self.incoming.work()
    self.arrival_received = True

  def read(self):
    if self.focus is None:
      return None
    label = self.builder.label if self.focus is SELF else self.vm.label(self.focus)
    return label["input"]

  def can_right(self):
    return not self.right_stack.empty() or not self.incoming.empty()

  def right(self):
    if not self.can_right():
      raise ValueError("input not yet available")
    self.left_stack.push(self.focus)
    if not self.right_stack.empty():
      self.focus = self.right_stack.pop()
    else:
      # Extra bounded work allows several client operations in one physical
      # step without depending on a once-per-input rotation cadence.
      self.incoming.work()
      self.focus = self.incoming.pop()
      self.incoming.work()

  def left(self):
    if self.left_stack.empty():
      raise ValueError("cannot move left of the input origin")
    self.right_stack.push(self.focus)
    self.focus = self.left_stack.pop()

  def copy_from(self, other):
    if self.vm is not other.vm or self.builder is not other.builder:
      raise ValueError("heads must belong to the same scaffold step")
    if self.arrival_received != other.arrival_received:
      raise ValueError("heads must have received the same arrival")
    self.focus = other.focus
    self.left_stack.copy_from(other.left_stack)
    self.right_stack.copy_from(other.right_stack)
    for role in RTQueueView.NAMES:
      self.incoming.s[role].copy_from(other.incoming.s[role])
    for counter in ("m", "c"):
      target = getattr(self.incoming, counter)
      source = getattr(other.incoming, counter)
      target.pos.copy_from(source.pos)
      target.neg.copy_from(source.neg)
    self.incoming.phase = other.incoming.phase

  def finalize(self):
    self.incoming.work()
    self.left_stack.finalize()
    self.right_stack.finalize()
    self.incoming.finalize()
    self.builder.ptr[self.name + ".focus"] = self.focus
