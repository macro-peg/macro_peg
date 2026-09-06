"""Pack a finite sequence of local scaffold transitions into one input node.

Virtual cells have (physical node, finite slot) addresses. Queries of slots
already made in this round use their construction expressions. Queries of old
nodes read slot-indexed fields. Self references survive as tagged self edges;
there are no queries of the physical node under construction and no equality
test on pointers. This pass does not derive how many transitions to schedule.
"""
from dataclasses import dataclass

from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, NULL, pointer, read,
                              edge, present, symbol)
from scaffold_circuit import (Value, choose, choose_pointer, neg,
                              conjunction as AND, disjunction as OR)


@dataclass
class Address:
  local: dict
  prior: tuple
  tag: Value


class RoundBuilder:
  def __init__(self, stages, input_symbols=None, alphabet=None):
    self.stages = tuple(stages)
    if not self.stages:
      raise ValueError("a round needs at least one transition")
    self.source = self.stages[0]
    self.alphabet = self.source.alphabet if alphabet is None else tuple(alphabet)
    self.input_symbols = (tuple({char: symbol(char) for char in stage.alphabet}
                                 for stage in self.stages)
                          if input_symbols is None else tuple(input_symbols))
    if len(self.input_symbols) != len(self.stages) or any(
        set(mapping) != set(stage.alphabet)
        for mapping, stage in zip(self.input_symbols, self.stages)):
      raise ValueError("one complete input-symbol substitution per transition required")
    for stage in self.stages:
      if (stage.initial != self.source.initial or
          set(stage.pointers) != set(self.source.pointers) or
          stage.alphabet != self.source.alphabet):
        raise ValueError("round transitions need a common state schema and alphabet")
    self.tags = tuple(range(len(self.stages)))
    self.bits = (len(self.tags) - 1).bit_length()
    self.labels, self.pointers, self.initial = {}, {}, {}
    self.slot_labels, self.slot_pointers = [], []
    self.zero_tag = Value.constant(0).recode(self.tags)
    self.empty = Address({}, NULL, self.zero_tag)

  @staticmethod
  def label(slot, key):
    return f"s{slot}.label.{key}"

  @staticmethod
  def field(slot, key):
    return f"s{slot}.edge.{key}"

  @staticmethod
  def tag_bit(slot, key, bit):
    return f"s{slot}.tag.{key}.{bit}"

  def select(self, guard, yes, no):
    if guard == TRUE: return yes
    if guard == FALSE: return no
    local = {slot: choose(guard, yes.local.get(slot, FALSE), no.local.get(slot, FALSE))
             for slot in yes.local.keys() | no.local.keys()}
    return Address({slot: g for slot, g in local.items() if g != FALSE},
                    choose_pointer(guard, yes.prior, no.prior),
                    Value.select(guard, yes.tag, no.tag))

  def exists(self, target):
    return OR(*target.local.values(), present(target.prior) if target.prior != NULL else FALSE)

  def possible_tags(self, tag):
    if tag.valid == FALSE: return ()
    if all(bit in (TRUE, FALSE) for bit in tag.bits):
      index = sum(1 << i for i, bit in enumerate(tag.bits) if bit == TRUE)
      return (tag.domain[index],) if index < len(tag.domain) else ()
    return self.tags

  def read(self, target, key):
    values = [AND(guard, self.slot_labels[slot][key])
              for slot, guard in target.local.items()]
    if target.prior != NULL:
      values.extend(AND(target.tag.eq(slot), read(target.prior, self.label(slot, key)))
                    for slot in self.possible_tags(target.tag) if target.tag.eq(slot) != FALSE)
    return OR(*values)

  def follow(self, target, key):
    result = self.empty
    if target.prior != NULL:
      prior, bits = NULL, [FALSE] * self.bits
      for slot in self.possible_tags(target.tag):
        guard = target.tag.eq(slot)
        if guard == FALSE: continue
        prior = choose_pointer(guard, edge(target.prior, self.field(slot, key)), prior)
        bits = [choose(guard, read(target.prior, self.tag_bit(slot, key, bit)), value)
                for bit, value in enumerate(bits)]
      result = Address({}, prior, Value.encoded(self.tags, bits))
    for slot, guard in target.local.items():
      result = self.select(guard, self.slot_pointers[slot][key], result)
    return result

  def translate(self, expression, slot, root, memo):
    # Resolve guards first. An ingestion slot often disables the entire source
    # transition; eagerly translating both branches would expand it anyway.
    work, value = [("eval", expression)], None
    while work:
      task = work.pop()
      action = task[0]
      if action == "save": memo[task[1]] = value
      elif action == "not": value = neg(value)
      elif action == "read": value = self.read(value, task[1])
      elif action == "edge": value = self.follow(value, task[1])
      elif action == "present": value = self.exists(value)
      elif action == "logical":
        op, args, index, values = task[1:]
        values.append(value)
        if value == (FALSE if op == "and" else TRUE):
          pass
        elif index + 1 == len(args):
          value = (AND if op == "and" else OR)(*values)
        else:
          work.append(("logical", op, args, index + 1, values))
          work.append(("eval", args[index + 1]))
      elif action == "branch":
        yes, no = task[1:]
        if value in (TRUE, FALSE):
          work.append(("eval", yes if value == TRUE else no))
        else:
          work.append(("yes", value, no))
          work.append(("eval", yes))
      elif action == "yes":
        work.append(("select", task[1], value))
        work.append(("eval", task[2]))
      elif action == "select": value = self.select(task[1], task[2], value)
      else:
        expr = task[1]
        if id(expr) in memo:
          value = memo[id(expr)]
          continue
        work.append(("save", id(expr)))
        op, *args = expr
        if op == "const": value = TRUE if args[0] else FALSE
        elif op == "symbol": value = self.input_symbols[slot][args[0]]
        elif op == "self": value = Address({slot: TRUE}, NULL, self.zero_tag)
        elif op == "null": value = self.empty
        elif op in ("pointer", "old", "exists"):
          target = root
          for key in args[0]: target = self.follow(target, key)
          value = self.read(target, args[1]) if op == "old" else \
            self.exists(target) if op == "exists" else target
        elif op in ("read", "edge"):
          work.append((op, args[1])); work.append(("eval", args[0]))
        elif op in ("not", "present"):
          work.append((op,)); work.append(("eval", args[0]))
        elif op in ("and", "or"):
          value = TRUE if op == "and" else FALSE
          if args:
            work.append(("logical", op, args, 0, []))
            work.append(("eval", args[0]))
        elif op == "select":
          work.append(("branch", args[1], args[2]))
          work.append(("eval", args[0]))
        else: raise ValueError(f"unsupported source expression: {op}")
    return value

  def build(self):
    root = Address({}, pointer(()), Value.constant(self.tags[-1]).recode(self.tags))
    for slot, source in enumerate(self.stages):
      memo = {}
      labels = {key: self.translate(expr, slot, root, memo)
                for key, expr in source.labels.items()}
      pointers = {key: self.translate(expr, slot, root, memo)
                  for key, expr in source.pointers.items()}
      self.slot_labels.append(labels)
      self.slot_pointers.append(pointers)
      for key, expression in labels.items():
        name = self.label(slot, key)
        self.initial[name], self.labels[name] = source.initial[key], expression
      for key, address in pointers.items():
        target, tag = address.prior, address.tag
        for local, guard in address.local.items():
          target = choose_pointer(guard, SELF, target)
          tag = Value.select(guard, Value.constant(local), tag)
        self.pointers[self.field(slot, key)] = target
        tag = tag.recode(self.tags)
        for bit, expression in enumerate(tag.bits):
          name = self.tag_bit(slot, key, bit)
          self.initial[name], self.labels[name] = False, expression
      root = Address({slot: TRUE}, NULL, self.zero_tag)
    accepting = self.label(self.tags[-1], self.stages[-1].accepting)
    return Scaffold(self.initial, self.labels, self.pointers, accepting, self.alphabet)


def pack_round(stages, input_symbols=None, alphabet=None):
  return RoundBuilder(stages, input_symbols, alphabet).build()
