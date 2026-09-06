"""Predicated finite-value and local-pointer equations for scaffold lowering.

This is a construction-time API. Its Python dictionaries are finite expression
maps; the emitted machine has only Boolean labels, pointer fields, and ordinary
PEG rules. Ref distinguishes a cell allocated during this tick from an old cell
so reading a new cell uses its construction-time fields, never recursive queries
of the new scaffold node. No pointer identity operation is supplied.
"""
from dataclasses import dataclass

from symbolic_sca2peg import (Scaffold, Expr, TRUE, FALSE, SELF, NULL, old, symbol,
                              pointer, read, edge, present, select)


def neg(a):
  if a == TRUE: return FALSE
  if a == FALSE: return TRUE
  if a[0] == "not": return a[1]
  return Expr(("not", a))


def conjunction(*args):
  result, seen = [], set()
  for arg in args:
    if arg == FALSE: return FALSE
    if arg != TRUE and arg not in seen:
      result.append(arg); seen.add(arg)
  return FALSE if any(neg(x) in seen for x in result) else \
    TRUE if not result else result[0] if len(result) == 1 else Expr(("and", *result))


def disjunction(*args):
  result, seen = [], set()
  for arg in args:
    if arg == TRUE: return TRUE
    if arg != FALSE and arg not in seen:
      result.append(arg); seen.add(arg)
  return TRUE if any(neg(x) in seen for x in result) else \
    FALSE if not result else result[0] if len(result) == 1 else Expr(("or", *result))


def choose(guard, yes, no):
  if guard == TRUE or yes == no: return yes
  if guard == FALSE: return no
  if yes == TRUE and no == FALSE: return guard
  if yes == FALSE and no == TRUE: return neg(guard)
  return disjunction(conjunction(guard, yes), conjunction(neg(guard), no))


def choose_pointer(guard, yes, no):
  if guard == TRUE or yes == no: return yes
  if guard == FALSE: return no
  return select(guard, yes, no)


class Value:
  """A finite tagged value with a shared binary encoding and a validity bit."""
  def __init__(self, cases):
    cases = {value: guard for value, guard in cases.items() if guard != FALSE}
    self.domain = tuple(cases)
    self.valid = disjunction(*cases.values())
    self.bits = tuple(disjunction(*(guard for index, guard in enumerate(cases.values())
                                   if index & (1 << bit)))
                      for bit in range(max(0, (len(cases) - 1).bit_length())))

  @classmethod
  def encoded(cls, domain, bits, valid=TRUE):
    result = object.__new__(cls)
    result.domain, result.bits, result.valid = tuple(domain), tuple(bits), valid
    return result

  @classmethod
  def constant(cls, value):
    return cls({value: TRUE})

  @property
  def cases(self):
    return {value: self.eq(value) for value in self.domain}

  def eq(self, value):
    if value not in self.domain: return FALSE
    index = self.domain.index(value)
    return conjunction(self.valid, *(bit if index & (1 << i) else neg(bit)
                                     for i, bit in enumerate(self.bits)))

  def recode(self, domain):
    domain = tuple(domain)
    if domain == self.domain: return self
    if not set(self.domain) <= set(domain):
      raise ValueError("cannot discard variants of a finite value")
    bits = tuple(disjunction(*(self.eq(value) for index, value in enumerate(domain)
                               if index & (1 << bit)))
                 for bit in range(max(0, (len(domain) - 1).bit_length())))
    return Value.encoded(domain, bits, self.valid)

  def map(self, function):
    result = {}
    for value, guard in self.cases.items():
      mapped = function(value)
      result[mapped] = disjunction(result.get(mapped, FALSE), guard)
    return Value(result)

  def cycle(self, direction=1):
    """Rotate a power-of-two domain by one with a binary carry circuit."""
    if direction not in (-1, 1) or not self.domain or len(self.domain) & (len(self.domain) - 1):
      raise ValueError("unit cyclic movement requires a power-of-two domain")
    carry, bits = TRUE, []
    for bit in self.bits:
      bits.append(choose(carry, neg(bit), bit))
      carry = conjunction(carry, bit if direction == 1 else neg(bit))
    return Value.encoded(self.domain, bits, self.valid)

  def equal(self, other):
    if self.domain == other.domain:
      return conjunction(self.valid, other.valid,
        *(choose(a, b, neg(b)) for a, b in zip(self.bits, other.bits)))
    return disjunction(*(conjunction(guard, other.eq(value))
                         for value, guard in self.cases.items()))

  @staticmethod
  def select(guard, yes, no):
    if guard == TRUE: return yes
    if guard == FALSE: return no
    domain = tuple(dict.fromkeys((*no.domain, *yes.domain)))
    yes, no = yes.recode(domain), no.recode(domain)
    return Value.encoded(domain, (choose(guard, a, b) for a, b in zip(yes.bits, no.bits)),
                         choose(guard, yes.valid, no.valid))


@dataclass(frozen=True)
class Ref:
  new: tuple = FALSE
  prior: tuple = NULL

  def present(self):
    return disjunction(self.new, present(self.prior) if self.prior != NULL else FALSE)

  @staticmethod
  def select(guard, yes, no):
    return Ref(choose(guard, yes.new, no.new),
               choose_pointer(guard, yes.prior, no.prior))

  def expression(self):
    return choose_pointer(self.new, SELF, self.prior)


NEW = Ref(TRUE, NULL)
EMPTY = Ref()
PREVIOUS = Ref(FALSE, pointer(()))


class Circuit:
  def __init__(self, alphabet="ab", check_invariants=True):
    self.alphabet = alphabet
    self.check_invariants = check_invariants
    self.domains, self.initial, self.labels, self.pointers = {}, {}, {}, {}
    self.current_values, self.current_refs = {}, {}
    self.label_ids = {}

  def _label(self, key, value):
    pair = key, value
    if pair not in self.label_ids:
      self.label_ids[pair] = f"v{len(self.label_ids)}"
    return self.label_ids[pair]

  def scalar(self, key, domain, initial):
    domain = tuple(domain)
    if initial not in domain or len(set(domain)) != len(domain):
      raise ValueError("finite scalar domain must be distinct and include its initial value")
    if key in self.domains:
      if self.domains[key] != (domain, initial):
        raise ValueError(f"inconsistent scalar declaration: {key}")
      return
    self.domains[key] = domain, initial
    for bit in range(max(0, (len(domain) - 1).bit_length())):
      name = self._label(key, bit)
      self.initial[name] = bool(domain.index(initial) & (1 << bit))
      self.labels[name] = FALSE

  def get(self, target, key, domain, initial):
    self.scalar(key, domain, initial)
    current = self.current_values.get(key, Value.constant(initial)).recode(domain)
    bits = tuple(choose(target.new, current.bits[bit],
                        read(target.prior, self._label(key, bit)))
                 for bit in range(len(current.bits)))
    return Value.encoded(domain, bits, target.present())

  def put(self, key, value, domain=None, initial=None):
    if key not in self.domains:
      if domain is None: raise ValueError(f"undeclared scalar: {key}")
      self.scalar(key, domain, initial)
    value = value.recode(self.domains[key][0])
    self.current_values[key] = value
    for bit, expr in enumerate(value.bits):
      self.labels[self._label(key, bit)] = expr

  def get_ref(self, target, key):
    self.pointers.setdefault(key, NULL)
    local = self.current_refs.get(key, EMPTY)
    return Ref(choose(target.new, local.new, FALSE),
               choose_pointer(target.new, local.prior, edge(target.prior, key)))

  def put_ref(self, key, value):
    self.current_refs[key] = value
    self.pointers[key] = value.expression()

  def input(self):
    return Value({char: symbol(char) for char in self.alphabet})

  def require(self, condition, enabled=TRUE):
    if not self.check_invariants: return
    if not hasattr(self, "fault"):
      self.fault = self.get(PREVIOUS, "circuit.fault", (False, True), False).eq(True)
    self.fault = disjunction(self.fault, conjunction(enabled, neg(condition)))

  def machine(self, accepting, initial_accepting=False):
    if hasattr(self, "fault"):
      self.put("circuit.fault", Value.select(self.fault, Value.constant(True), Value.constant(False)))
      accepting = conjunction(accepting, neg(self.fault))
    # Unwritten cell fields have their declared defaults; this matters when a
    # later node aliases a slot never allocated in a particular branch.
    for key, (domain, initial) in self.domains.items():
      if key not in self.current_values:
        for bit in range(max(0, (len(domain) - 1).bit_length())):
          self.labels[self._label(key, bit)] = TRUE if domain.index(initial) & (1 << bit) else FALSE
    name = "accept"
    if name in self.initial: raise ValueError("reserved acceptance label")
    self.initial[name] = initial_accepting
    self.labels[name] = accepting
    return Scaffold(self.initial, self.labels, self.pointers, name, self.alphabet)
