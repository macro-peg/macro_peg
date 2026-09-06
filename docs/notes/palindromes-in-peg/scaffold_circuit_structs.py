"""Persistent stack and tape operations emitted as finite scaffold equations.

All loops here range over a fixed construction-time cell layout. Several cells
may be allocated on the same physical scaffold node. Their labels and pointers
are read through Circuit's local snapshots until the node is emitted.
"""
from scaffold_circuit import (Circuit, Value, Ref, EMPTY, NEW, PREVIOUS,
                              TRUE, FALSE, choose, conjunction, disjunction, neg)


class StackPool:
  def __init__(self, circuit, layout, payload=(None,)):
    self.circuit = circuit
    self.tags = tuple((name, index) for name, count in layout.items() for index in range(count))
    if not self.tags: raise ValueError("nonempty finite cell layout required")
    self.layout, self.used, self.payload = dict(layout), {}, tuple(payload)
    self.tag_ids = {tag: index for index, tag in enumerate(self.tags)}
    self.prefix = f"pool{len(getattr(circuit, '_pools', []))}"
    if not hasattr(circuit, '_pools'): circuit._pools = []
    circuit._pools.append(self)

  def key(self, tag, field):
    return f"{self.prefix}.c{self.tag_ids[tag]}.{field}"

  def scalar(self, ref, tag, field, domain, initial):
    # Tag equality guards are pairwise disjoint. Encode a field read as one
    # sum of products per bit instead of a long nest of binary multiplexers.
    # This also shares the validity test across all slots of this pool.
    default = Value.constant(initial).recode(domain)
    choices = []
    for variant in self.tags:
      condition = tag.eq(variant)
      if condition != FALSE:
        value = self.circuit.get(ref, self.key(variant, field), domain, initial)
        choices.append((condition, value))
    missing = neg(disjunction(*(condition for condition, _ in choices)))
    bits = tuple(disjunction(conjunction(missing, bit),
                   *(conjunction(condition, value.bits[index]) for condition, value in choices))
                 for index, bit in enumerate(default.bits))
    valid = disjunction(missing,
              *(conjunction(condition, value.valid) for condition, value in choices))
    return Value.encoded(domain, bits, valid)

  def pointer(self, ref, tag, field):
    answer = EMPTY
    for variant in self.tags:
      condition = tag.eq(variant)
      if condition != FALSE:
        value = self.circuit.get_ref(ref, self.key(variant, field))
        answer = Ref.select(condition, value, answer)
    return answer

  def allocate(self, name, previous, previous_tag, value, data, enabled, slot=None):
    index = self.used.get(name, 0) if slot is None else slot
    if index >= self.layout[name]:
      raise ValueError(f"finite stack cell layout exhausted for {name}")
    self.used[name] = max(self.used.get(name, 0), index + 1)
    tag = name, index
    self.circuit.put_ref(self.key(tag, "below"), Ref.select(enabled, previous,
      self.circuit.current_refs.get(self.key(tag, "below"), EMPTY)))
    self.circuit.put(self.key(tag, "tag"),
      Value.select(enabled, previous_tag, self.circuit.current_values.get(self.key(tag, "tag"),
        Value.constant(self.tags[0]))), self.tags, self.tags[0])
    self.circuit.put_ref(self.key(tag, "value"), Ref.select(enabled, value,
      self.circuit.current_refs.get(self.key(tag, "value"), EMPTY)))
    self.circuit.put(self.key(tag, "data"),
      Value.select(enabled, data, self.circuit.current_values.get(self.key(tag, "data"),
        Value.constant(self.payload[0]))), self.payload, self.payload[0])
    return NEW, Value.constant(tag).recode(self.tags)


class Stack:
  def __init__(self, pool, name, allocation_name=None):
    self.pool, self.name, self.circuit = pool, name, pool.circuit
    self.allocation_name = name if allocation_name is None else allocation_name
    self.root_key = f"{pool.prefix}.{name}.root"
    self.tag_key = f"{pool.prefix}.{name}.tag"
    self.top = self.circuit.get_ref(PREVIOUS, self.root_key)
    self.tag = self.circuit.get(PREVIOUS, self.tag_key, pool.tags, pool.tags[0])

  def empty(self):
    return neg(self.top.present())

  def peek(self):
    return (self.pool.pointer(self.top, self.tag, "value"),
            self.pool.scalar(self.top, self.tag, "data", self.pool.payload, self.pool.payload[0]))

  def pop(self, enabled=TRUE):
    value = self.peek()
    self.drop(enabled)
    return value

  def drop(self, enabled=TRUE):
    below = self.pool.pointer(self.top, self.tag, "below")
    tag = self.pool.scalar(self.top, self.tag, "tag", self.pool.tags, self.pool.tags[0])
    self.top = Ref.select(enabled, below, self.top)
    self.tag = Value.select(enabled, tag, self.tag)

  def push(self, value=EMPTY, data=None, enabled=TRUE, slot=None):
    if enabled == FALSE: return
    if data is None: data = Value.constant(self.pool.payload[0])
    top, tag = self.pool.allocate(self.allocation_name, self.top, self.tag, value, data, enabled, slot)
    self.top = Ref.select(enabled, top, self.top)
    self.tag = Value.select(enabled, tag, self.tag)

  def clear(self, enabled=TRUE):
    self.top = Ref.select(enabled, EMPTY, self.top)

  def copy_from(self, other, enabled=TRUE):
    if self.pool is not other.pool:
      raise ValueError("aliased stacks must share a declared finite cell pool")
    self.top = Ref.select(enabled, other.top, self.top)
    self.tag = Value.select(enabled, other.tag, self.tag)

  def finalize(self):
    self.circuit.put_ref(self.root_key, self.top)
    self.circuit.put(self.tag_key, self.tag)


class Tape:
  def __init__(self, circuit, name, alphabet, slots=1, blank="_", pool=None):
    self.circuit, self.name, self.alphabet, self.blank = circuit, name, tuple(alphabet), blank
    # Moving copies the focus symbol, never a stack cell. Left and right cell
    # tags therefore have separate finite domains, avoiding an unnecessary
    # cross-product during computed-cell reads.
    sizes = (slots, slots) if isinstance(slots, int) else tuple(slots)
    if pool is None:
      self.left, self.right = [Stack(StackPool(circuit, {name + side: count}, self.alphabet), name + side)
                               for side, count in zip((".l", ".r"), sizes)]
    else:
      # A Program may assign the same construction slot to mutually exclusive
      # tape moves. Stack roots stay distinct; only their cell storage is shared.
      self.left, self.right = [Stack(pool, name + side, "cells") for side in (".l", ".r")]
    self.symbol_key = name + ".symbol"
    self.focus = circuit.get(PREVIOUS, self.symbol_key, self.alphabet, blank)

  def write(self, value, enabled=TRUE):
    self.focus = Value.select(enabled, value.recode(self.alphabet), self.focus)

  def reset(self, enabled=TRUE):
    self.left.clear(enabled); self.right.clear(enabled)
    self.write(Value.constant(self.blank), enabled)

  def move(self, direction, enabled=TRUE, slot=None):
    if enabled == FALSE: return
    pushed, popped = (self.left, self.right) if direction == 1 else (self.right, self.left)
    if direction == -1: self.circuit.require(neg(popped.empty()), enabled)
    empty = popped.empty()
    _, value = popped.pop(enabled)
    if value.domain != self.alphabet:
      value = Value({char: value.eq(char) for char in self.alphabet})
      self.circuit.require(value.valid, conjunction(enabled, neg(empty)))
    pushed.push(data=self.focus, enabled=enabled, slot=slot)
    self.write(Value.select(empty, Value.constant(self.blank), value), enabled)

  def finalize(self):
    self.left.finalize(); self.right.finalize()
    self.circuit.put(self.symbol_key, self.focus)


class Counter:
  def __init__(self, pool, name, allocation_name=None):
    pools = pool if isinstance(pool, dict) else {"pos": pool, "neg": pool}
    self.pos, self.neg = (Stack(pools[side], name + "." + side, allocation_name)
                          for side in ("pos", "neg"))

  def positive(self):
    return neg(self.pos.empty())

  def negative(self):
    return neg(self.neg.empty())

  def zero(self):
    return conjunction(self.pos.empty(), self.neg.empty())

  def inc(self, enabled=TRUE, slot=None):
    empty = self.neg.empty()
    self.neg.drop(conjunction(enabled, neg(empty)))
    self.pos.push(enabled=conjunction(enabled, empty), slot=slot)

  def dec(self, enabled=TRUE, slot=None):
    empty = self.pos.empty()
    self.pos.drop(conjunction(enabled, neg(empty)))
    self.neg.push(enabled=conjunction(enabled, empty), slot=slot)

  def reset(self, enabled=TRUE):
    self.pos.clear(enabled); self.neg.clear(enabled)

  def copy_from(self, other, enabled=TRUE):
    self.pos.copy_from(other.pos, enabled); self.neg.copy_from(other.neg, enabled)

  def finalize(self):
    self.pos.finalize(); self.neg.finalize()


class Queue:
  NAMES = ("F", "B", "Fr", "Br", "WF", "WB", "B2")

  def __init__(self, pool, counter_pool, name, shared_slots=False):
    pools = pool if isinstance(pool, dict) else {role: pool for role in self.NAMES}
    counter_pools = counter_pool if isinstance(counter_pool, dict) else {"m": counter_pool, "c": counter_pool}
    self.circuit, self.name = pools["F"].circuit, name
    self.shared_slots = shared_slots
    allocation = "cells" if shared_slots else None
    self.s = {role: Stack(pools[role], f"{name}.{role}", allocation) for role in self.NAMES}
    self.m, self.c = (Counter(counter_pools[role], name + "." + role, allocation)
                      for role in ("m", "c"))
    self.phase_key = name + ".phase"
    self.phase = self.circuit.get(PREVIOUS, self.phase_key, ("idle", "rev", "copy"), "idle")

  def _phase(self, value, enabled):
    self.phase = Value.select(enabled, Value.constant(value), self.phase)

  def _slot(self, index=0):
    return index if self.shared_slots else None

  def push(self, value, enabled=TRUE):
    idle = self.phase.eq("idle")
    self.s["B"].push(value, enabled=conjunction(enabled, idle), slot=self._slot())
    self.s["B2"].push(value, enabled=conjunction(enabled, neg(idle)), slot=self._slot())
    self.c.dec(enabled, slot=self._slot())

  def pop(self, enabled=TRUE):
    self.circuit.require(neg(self.s["F"].empty()), enabled)
    value, _ = self.s["F"].pop(enabled)
    self.c.dec(enabled, slot=self._slot())
    rotating = conjunction(enabled, neg(self.phase.eq("idle")))
    self.m.dec(rotating, slot=self._slot())
    self._maybe_finish(rotating)
    return value

  def empty(self):
    return conjunction(self.s["F"].empty(), self.s["B"].empty(), self.phase.eq("idle"))

  def clear(self, enabled=TRUE):
    for stack in self.s.values(): stack.clear(enabled)
    self.m.reset(enabled); self.c.reset(enabled)
    self._phase("idle", enabled)

  def _start(self, enabled):
    self.s["WF"].copy_from(self.s["F"], enabled)
    self.s["WB"].copy_from(self.s["B"], enabled)
    for name in ("Fr", "Br", "B2"): self.s[name].clear(enabled)
    self.m.reset(enabled)
    self._phase("rev", enabled)

  def _maybe_finish(self, enabled):
    finish = conjunction(enabled, self.phase.eq("copy"), self.m.zero())
    self.s["F"].copy_from(self.s["Br"], finish)
    self.s["B"].copy_from(self.s["B2"], finish)
    for name in ("Br", "B2", "Fr"): self.s[name].clear(finish)
    self._phase("idle", finish)

  def _unit(self, enabled):
    reverse = conjunction(enabled, self.phase.eq("rev"))
    copy = conjunction(enabled, self.phase.eq("copy"))
    rear = conjunction(reverse, neg(self.s["WB"].empty()))
    front = conjunction(reverse, neg(self.s["WF"].empty()))
    value, _ = self.s["WB"].pop(rear)
    self.s["Br"].push(value, enabled=rear, slot=self._slot())
    self.c.inc(rear, slot=self._slot()); self.c.inc(rear, slot=self._slot(1))
    value, _ = self.s["WF"].pop(front)
    self.s["Fr"].push(value, enabled=front, slot=self._slot())
    self.m.inc(front, slot=self._slot())
    finished_reversal = conjunction(reverse, neg(disjunction(rear, front)))
    self._phase("copy", finished_reversal)
    self._maybe_finish(finished_reversal)
    copying = conjunction(copy, self.m.positive(), neg(self.s["Fr"].empty()))
    value, _ = self.s["Fr"].pop(copying)
    self.s["Br"].push(value, enabled=copying, slot=self._slot())
    self.m.dec(copying, slot=self._slot())
    self._maybe_finish(copy)

  def work(self, enabled=TRUE):
    if self.shared_slots:
      raise ValueError("shared queue cells require one work_unit per physical transition")
    for _ in range(3):
      self.work_unit(enabled)

  def work_unit(self, enabled=TRUE):
    """One rotation instruction; shared-slot users must schedule it explicitly."""
    self._start(conjunction(enabled, self.phase.eq("idle"), self.c.negative()))
    self._unit(conjunction(enabled, neg(self.phase.eq("idle"))))

  def copy_from(self, other, enabled=TRUE):
    for name in self.NAMES: self.s[name].copy_from(other.s[name], enabled)
    self.m.copy_from(other.m, enabled); self.c.copy_from(other.c, enabled)
    self.phase = Value.select(enabled, other.phase, self.phase)

  def finalize(self):
    for stack in self.s.values(): stack.finalize()
    self.m.finalize(); self.c.finalize()
    self.circuit.put(self.phase_key, self.phase)
