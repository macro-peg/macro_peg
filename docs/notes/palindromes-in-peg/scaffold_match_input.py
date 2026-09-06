"""A frozen reversed prefix followed by an incrementally arriving text.

Two global input heads supply the prefix snapshots: before its first letter,
and on its last letter. The reversed pattern needs no copied/reversed array.
Text heads denote cuts and inspect their next cell, so they can wait at the
arrival frontier without moving beyond it or inventing an end marker.
"""
from scaffold_circuit import (Circuit, Value, Ref, NEW, PREVIOUS, TRUE, FALSE,
                              conjunction, disjunction, neg, choose)
from scaffold_circuit_structs import StackPool
from scaffold_circuit_input import InputHead


def input_pools(circuit, operations):
  """Fixed construction counts: each name maps to (append, right, left)."""
  def pool(layout):
    return StackPool(circuit, layout if any(layout.values()) else {"unused": 1})
  heads = {side: pool({name + "." + side: counts[index] for name, counts in operations.items()})
           for side, index in (("l", 1), ("r", 2))}
  units = {name: 3 * (counts[0] + 2 * counts[1]) for name, counts in operations.items()}
  rear = pool({name + ".in." + role: counts[0] for name, counts in operations.items()
               for role in ("B", "B2")})
  reverse = pool({name + ".in.Fr": amount for name, amount in units.items()})
  front = pool({name + ".in.Br": 2 * amount for name, amount in units.items()})
  queues = {"F": front, "WF": front, "Br": front, "B": rear, "WB": rear,
            "B2": rear, "Fr": reverse}
  counters = {}
  for role in ("m", "c"):
    counters[role] = {}
    for side in ("pos", "neg"):
      counts = {name + ".in." + role + "." + side:
                (units[name] if role == "m" else 2 * units[name]) if side == "pos" else
                (units[name] + calls[1] if role == "m" else calls[0] + calls[1])
                for name, calls in operations.items()}
      counters[role][side] = pool(counts)
  return heads, queues, counters


class MatchInput:
  def __init__(self, pools, name):
    self.pattern = InputHead(*pools, name + ".pattern")
    self.text = InputHead(*pools, name + ".text")
    self.circuit = self.pattern.circuit
    self.mode_key = name + ".text_mode"
    self.text_mode = self.circuit.get(PREVIOUS, self.mode_key, (False, True), False).eq(True)

  def start(self, snapshot, enabled=TRUE):
    self.pattern.copy_from(snapshot, enabled)
    self.text.reset(enabled)
    self.text_mode = choose(enabled, neg(snapshot.focus.present()), self.text_mode)

  def append(self, cell, enabled=TRUE):
    self.text.append(cell, enabled)

  def available(self):
    return choose(self.text_mode, self.text.can_right(), self.pattern.focus.present())

  def read(self):
    target = Ref.select(self.text_mode, self.text.peek_right(), self.pattern.focus)
    value = self.circuit.get(target, "input", tuple("ab"), "a")
    return Value.select(target.present(), value, Value.constant(None))

  def move(self, direction, enabled=TRUE):
    if direction not in (-1, 1):
      raise ValueError("unit direction required")
    text_mode = self.text_mode
    if direction == 1:
      self.pattern.left(conjunction(enabled, neg(text_mode)))
      self.text.right(conjunction(enabled, text_mode))
      reached_text = conjunction(enabled, neg(text_mode), neg(self.pattern.focus.present()))
      self.text_mode = disjunction(text_mode, reached_text)
    else:
      at_text_start = neg(self.text.focus.present())
      self.text.left(conjunction(enabled, text_mode, neg(at_text_start)))
      enter_pattern = conjunction(enabled, text_mode, at_text_start)
      self.pattern.right(conjunction(enabled, disjunction(neg(text_mode), at_text_start)))
      self.text_mode = conjunction(text_mode, neg(enter_pattern))

  def copy_from(self, other, enabled=TRUE):
    self.pattern.copy_from(other.pattern, enabled)
    self.text.copy_from(other.text, enabled)
    self.text_mode = choose(enabled, other.text_mode, self.text_mode)

  def finalize(self):
    # push/right already perform the queue's bounded maintenance. Frozen
    # snapshots need no additional rotation work on unrelated input steps.
    self.pattern.finalize(maintain=False)
    self.text.finalize(maintain=False)
    self.circuit.put(self.mode_key, Value.select(self.text_mode, Value.constant(True), Value.constant(False)))


def fixture():
  circuit = Circuit("ab!<>[]xy.")
  operations = {"global.begin": (1, 0, 0), "global.end": (1, 1, 0)}
  for name in ("A", "B"):
    operations[name + ".pattern"] = (0, 1, 1)
    operations[name + ".text"] = (1, 1, 1)
  pools = input_pools(circuit, operations)
  begin, end = [InputHead(*pools, "global." + name) for name in ("begin", "end")]
  a, b = [MatchInput(pools, name) for name in ("A", "B")]
  char = circuit.input()
  arrival = disjunction(char.eq("a"), char.eq("b"))
  circuit.put("input", Value.select(char.eq("b"), Value.constant("b"), Value.constant("a")),
              tuple("ab"), "a")
  active = circuit.get(PREVIOUS, "active", (False, True), False).eq(True)
  for head in (begin, end):
    head.append(NEW, arrival)
  end.right(arrival)
  for head in (a, b):
    head.append(NEW, conjunction(arrival, active))
  a.start(end, char.eq("!"))
  b.start(begin, char.eq("!"))
  active = disjunction(active, char.eq("!"))
  for head, right, left in ((a, ">", "<"), (b, "]", "[")):
    head.move(1, conjunction(active, char.eq(right), head.available()))
    # Left commands are expected to be legal in the test trace. The tape
    # primitives set the fault bit if the pattern's beginning is crossed.
    head.move(-1, conjunction(active, char.eq(left)))
  a.copy_from(b, char.eq("x"))
  b.copy_from(a, char.eq("y"))
  for head in (begin, end):
    head.finalize(maintain=False)
  for name, head in (("A", a), ("B", b)):
    value = head.read()
    circuit.put(name + ".observed", value, (None, "a", "b"), None)
    head.finalize()
  circuit.put("active", Value.select(active, Value.constant(True), Value.constant(False)))
  machine = circuit.machine(conjunction(active, a.available(), b.available(), a.read().equal(b.read())))
  return circuit, (a, b), machine
