"""Demand-driven construction of the observable part of a packed round.

The same virtual-node equations as scaffold_round are resolved only when a
query needs them. Persisted fields are closed under all old-node queries
reachable from acceptance. No input length or execution trace is specialized.
"""
from scaffold_round import RoundBuilder, Address
from scaffold_circuit import Value, choose_pointer
from symbolic_sca2peg import Scaffold, TRUE, FALSE, NULL, SELF, pointer


class MissingField(Exception):
  def __init__(self, kind, slot, key):
    self.field = kind, slot, key


class SlotFields(dict):
  def __init__(self, kind, slot):
    self.kind, self.slot = kind, slot

  def __missing__(self, key):
    raise MissingField(self.kind, self.slot, key)


class DemandRoundBuilder(RoundBuilder):
  def __init__(self, stages, input_symbols=None, alphabet=None):
    super().__init__(stages, input_symbols, alphabet)
    self.slot_labels = [SlotFields("label", i) for i in self.tags]
    self.slot_pointers = [SlotFields("pointer", i) for i in self.tags]
    self.memo = {}
    self.pending, self.requested = [], set()

  def need(self, kind, slot, key):
    field = kind, slot, key
    if field not in self.requested:
      self.requested.add(field)
      self.pending.append(field)

  def read(self, target, key):
    if target.prior != NULL:
      for slot in self.possible_tags(target.tag):
        if target.tag.eq(slot) != FALSE: self.need("label", slot, key)
    return super().read(target, key)

  def follow(self, target, key):
    if target.prior != NULL:
      for slot in self.possible_tags(target.tag):
        if target.tag.eq(slot) != FALSE: self.need("pointer", slot, key)
    return super().follow(target, key)

  def resolve(self, field):
    work, active = [field], {field}
    while work:
      kind, slot, key = current = work[-1]
      fields = self.slot_labels if kind == "label" else self.slot_pointers
      if key in fields[slot]:
        active.remove(work.pop())
        continue
      source = self.stages[slot]
      expression = (source.labels if kind == "label" else source.pointers)[key]
      root = Address({}, pointer(()), Value.constant(self.tags[-1]).recode(self.tags)) if slot == 0 else \
             Address({slot - 1: TRUE}, NULL, self.zero_tag)
      try:
        fields[slot][key] = self.translate(expression, slot, root, self.memo.setdefault(slot, {}))
        active.remove(work.pop())
      except MissingField as missing:
        if missing.field in active:
          raise ValueError("cyclic construction query in packed round") from missing
        active.add(missing.field)
        work.append(missing.field)
    return fields[slot][key]

  def build(self):
    accepting = self.stages[-1].accepting
    self.need("label", self.tags[-1], accepting)
    while self.pending:
      kind, slot, key = field = self.pending.pop()
      value = self.resolve(field)
      if kind == "label":
        name = self.label(slot, key)
        self.initial[name], self.labels[name] = self.stages[slot].initial[key], value
      else:
        target, tag = value.prior, value.tag
        for local, guard in value.local.items():
          target = choose_pointer(guard, SELF, target)
          tag = Value.select(guard, Value.constant(local), tag)
        self.pointers[self.field(slot, key)] = target
        for bit, expression in enumerate(tag.recode(self.tags).bits):
          name = self.tag_bit(slot, key, bit)
          self.initial[name], self.labels[name] = False, expression
    return Scaffold(self.initial, self.labels, self.pointers,
                     self.label(self.tags[-1], accepting), self.alphabet)


def pack_round_lazy(stages, input_symbols=None, alphabet=None):
  return DemandRoundBuilder(stages, input_symbols, alphabet).build()
