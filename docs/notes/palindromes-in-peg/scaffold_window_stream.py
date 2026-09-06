"""Bounded head motion over blocks of the original, unexpanded input.

During one input round a head is an old head origin plus a finite offset.
Blocks have length B, greater than the round's movement bound. Only the old
block and its two neighbors can be reached. Their zipper states are prepared
once, and only the selected final state is saved. Intermediate movements and
copies allocate no cells. Input blocks contain actual input-node addresses.

All input characters must belong to this stream, in consecutive transitions.
Frozen views, result stacks, and the complete PAL compiler are separate work.
"""
from copy import copy

from scaffold_circuit import (Circuit, Value, Ref, PREVIOUS, NEW, EMPTY, TRUE, FALSE,
                              choose, choose_pointer, conjunction as AND,
                              disjunction as OR, neg)
from scaffold_circuit_structs import Stack, StackPool, Queue
from scaffold_window_counter import Bits
from symbolic_sca2peg import pointer, edge


def clone_counter(source):
  result = copy(source)
  result.pos, result.neg = copy(source.pos), copy(source.neg)
  return result


def clone_queue(source):
  result = copy(source)
  result.s = {name: copy(stack) for name, stack in source.s.items()}
  result.m, result.c = clone_counter(source.m), clone_counter(source.c)
  return result


class BlockState:
  def __init__(self, bank, name):
    self.bank, self.name, self.circuit = bank, name, bank.circuit
    self.focus_key, self.live_key = name + ".block_end", name + ".live"
    self.focus = self.circuit.get_ref(PREVIOUS, self.focus_key)
    self.live = self.circuit.get(PREVIOUS, self.live_key, (False, True), True).eq(True)
    self.left = Stack(bank.cells, name + ".left")
    self.right = Stack(bank.cells, name + ".right")
    self.queue = Queue(bank.queue_cells, bank.counter_cells, name + ".incoming")

  def clone(self):
    result = copy(self)
    result.left, result.right = copy(self.left), copy(self.right)
    result.queue = clone_queue(self.queue)
    return result

  def complete_block(self, completed):
    self.queue.push(NEW, AND(completed, neg(self.live)))
    self.queue.work(completed)
    self.focus = Ref.select(AND(completed, self.live), NEW, self.focus)
    self.live = AND(self.live, neg(completed))

  def move_left(self, enabled):
    # A live block is not pushed. If it later completes while this head is
    # elsewhere, completion enqueues its actual endpoint. Before completion,
    # an empty right side already denotes that same live block.
    self.right.push(self.focus, enabled=AND(enabled, neg(self.live)))
    focus, _ = self.left.pop(enabled)
    self.focus = Ref.select(enabled, focus, self.focus)
    self.live = AND(self.live, neg(enabled))

  def move_right(self, enabled):
    self.left.push(self.focus, enabled=enabled)
    stacked, queued = neg(self.right.empty()), neg(self.queue.empty())
    from_queue = AND(enabled, neg(stacked), queued)
    a, _ = self.right.pop(AND(enabled, stacked))
    b = self.queue.pop(from_queue)
    self.queue.work(from_queue)
    self.focus = Ref.select(enabled, Ref.select(stacked, a, b), self.focus)
    self.live = choose(enabled, AND(neg(stacked), neg(queued)), self.live)

  def copy_from(self, other, enabled):
    self.focus = Ref.select(enabled, other.focus, self.focus)
    self.live = choose(enabled, other.live, self.live)
    self.left.copy_from(other.left, enabled)
    self.right.copy_from(other.right, enabled)
    self.queue.copy_from(other.queue, enabled)

  def finalize(self):
    self.circuit.put_ref(self.focus_key, self.focus)
    self.circuit.put(self.live_key,
      Value.select(self.live, Value.constant(True), Value.constant(False)))
    self.left.finalize()
    self.right.finalize()
    self.queue.finalize()


class WindowHead:
  def __init__(self, bank, name):
    self.bank, self.name = bank, name
    self.origin = Value.constant(name).recode(bank.names)
    low = Bits.load(bank.circuit, name + ".offset", bank.width)
    self.offset = Bits((*low.bits, FALSE, FALSE))
    self.radius = 0

  def variants(self, offset=None):
    offset = self.offset if offset is None else offset
    high, sign = offset.bits[-2:]
    directions = {-1: AND(high, sign), 0: AND(neg(high), neg(sign)),
                   1: AND(high, neg(sign))}
    for name in self.bank.names:
      origin = self.origin.eq(name)
      if origin == FALSE:
        continue
      for direction, guard in directions.items():
        state, valid = self.bank.neighbors[name][direction]
        yield state, AND(origin, guard), valid

  def describe(self, offset=None):
    endpoint, live, valid = EMPTY, FALSE, FALSE
    for state, guard, permitted in self.variants(offset):
      endpoint = Ref.select(guard, state.focus, endpoint)
      live = OR(live, AND(guard, state.live))
      valid = OR(valid, AND(guard, permitted))
    return endpoint, live, valid

  def location_flags(self, offset=None):
    # Availability does not need an endpoint. Avoid constructing discarded
    # pointer-selection DAGs for every prospective fixed movement.
    live, valid = FALSE, FALSE
    for state, guard, permitted in self.variants(offset):
      live = OR(live, AND(guard, state.live))
      valid = OR(valid, AND(guard, permitted))
    return live, valid

  def available(self):
    live, valid = self.location_flags()
    low = Bits(self.offset.bits[:self.bank.width])
    return AND(valid, OR(neg(live), low.unsigned_less(self.bank.phase)))

  def can_move(self, amount):
    candidate = self.offset.add(amount)
    live, valid = self.location_flags(candidate)
    low = Bits(candidate.bits[:self.bank.width])
    return AND(valid, OR(neg(live), low.unsigned_less(self.bank.phase), low.equal(self.bank.phase)))

  def move(self, amount, enabled=TRUE):
    if type(amount) is not int:
      raise ValueError("head motion must have a fixed finite distance")
    if enabled == FALSE or amount == 0:
      return
    if self.radius + abs(amount) >= self.bank.base:
      raise ValueError("head lineage exceeds the declared finite window")
    self.bank.circuit.require(self.can_move(amount), enabled)
    self.offset = Bits.select(enabled, self.offset.add(amount), self.offset)
    self.radius += abs(amount)

  def copy_from(self, other, enabled=TRUE):
    if other.bank is not self.bank:
      raise ValueError("head copies require a common stream")
    self.origin = Value.select(enabled, other.origin, self.origin)
    self.offset = Bits.select(enabled, other.offset, self.offset)
    self.radius = other.radius if enabled == TRUE else max(self.radius, other.radius)

  def move_selected(self, amount, enabled=TRUE):
    bound = max(map(abs, amount.domain))
    if self.radius + bound >= self.bank.base:
      raise ValueError("selected head motion exceeds the prepared finite window")
    before, after = self.offset, self.offset
    for delta in amount.domain:
      if not delta: continue
      guard = AND(enabled, amount.eq(delta))
      self.bank.circuit.require(self.can_move(delta), guard)
      after = Bits.select(guard, before.add(delta), after)
    self.offset, self.radius = after, self.radius + bound

  def read(self, enabled=TRUE):
    c, bank = self.bank.circuit, self.bank
    available = self.available()
    c.require(available, enabled)
    endpoint, live, _ = self.describe()
    low = Bits(self.offset.bits[:bank.width])
    full_distance = Bits(tuple(neg(bit) for bit in low.bits))
    live_distance = bank.phase.plus(low.negated()).add(-1)
    distance = Bits.select(live, live_distance, full_distance)
    endpoint = Ref.select(live, NEW, endpoint)
    for bit, enabled_bit in enumerate(distance.bits):
      endpoint = Ref.select(enabled_bit, bank.hop(endpoint, 1 << bit), endpoint)
    value = c.get(endpoint, bank.input_key, c.alphabet, c.alphabet[0])
    return Value.select(available, value, Value.constant(None))

  def read_relative(self, amount, enabled=TRUE):
    if self.radius + abs(amount) >= self.bank.base:
      raise ValueError("relative read exceeds the prepared head window")
    temporary = copy(self)
    temporary.offset = self.offset.add(amount)
    return temporary.read(enabled)

  def finalize(self):
    target = self.bank.states[self.name]
    for source, guard, valid in self.variants():
      self.bank.circuit.require(valid, guard)
      target.copy_from(source, guard)
    Bits(self.offset.bits[:self.bank.width]).store(self.bank.circuit, self.name + ".offset")
    target.finalize()


class WindowStream:
  def __init__(self, circuit, names, radius, prefix="stream"):
    if type(radius) is not int or radius < 0:
      raise ValueError("a nonnegative finite movement radius is required")
    self.circuit, self.names, self.prefix = circuit, tuple(names), prefix
    if not self.names or len(set(self.names)) != len(self.names):
      raise ValueError("distinct finite head names required")
    self.width = max(1, radius.bit_length())
    self.base = 1 << self.width
    self.phase_key, self.input_key = prefix + ".phase", prefix + ".input"
    old_phase = Bits.load(circuit, self.phase_key, self.width)
    completed = AND(*old_phase.bits)
    self.phase = old_phase.add(1)
    circuit.put(self.input_key, circuit.input(), circuit.alphabet, circuit.alphabet[0])
    self.cells = StackPool(circuit, {name + side: 1 for name in self.names for side in (".left", ".right")})
    def pool(layout):
      return StackPool(circuit, {f"{name}.incoming.{role}": count for name in self.names
                                for role, count in layout})
    front, rear, reverse = pool((("Br", 12),)), pool((("B", 1), ("B2", 1))), pool((("Fr", 6),))
    self.queue_cells = {"F": front, "WF": front, "Br": front,
                        "B": rear, "WB": rear, "B2": rear, "Fr": reverse}
    self.counter_cells = {"m": {"pos": pool((("m.pos", 6),)), "neg": pool((("m.neg", 8),))},
                          "c": {"pos": pool((("c.pos", 12),)), "neg": pool((("c.neg", 2),))}}
    self.states = {name: BlockState(self, name) for name in self.names}
    self.neighbors, self.jumps = {}, {}
    for name, state in self.states.items():
      state.complete_block(completed)
      center = state.clone()
      before, after = center.clone(), center.clone()
      can_left, can_right = neg(center.left.empty()), neg(center.live)
      before.move_left(can_left)
      after.move_right(can_right)
      self.neighbors[name] = {-1: (before, can_left), 0: (center, TRUE), 1: (after, can_right)}
    self.heads = {name: WindowHead(self, name) for name in self.names}

  def jump(self, distance):
    """An original-input predecessor by a fixed distance, in O(log B) fields."""
    if distance < 1:
      raise ValueError("positive original-input hop required")
    if distance not in self.jumps:
      name = f"{self.prefix}.back{distance}"
      self.jumps[distance] = name
      if distance == 1:
        path = ()
      elif distance % 2:
        path = (self.jump(distance // 2),) * 2
      else:
        half = distance // 2
        path = (self.jump(half),) + ((self.jump(half - 1),) if half > 1 else ())
      self.circuit.pointers[name] = pointer(path)
    return self.jumps[distance]

  def hop(self, target, distance):
    from_new = pointer(()) if distance == 1 else pointer((self.jump(distance - 1),))
    return Ref(FALSE, choose_pointer(target.new, from_new, edge(target.prior, self.jump(distance))))

  def select_head(self, which):
    cases = [(which.eq(name), head) for name, head in self.heads.items() if which.eq(name) != FALSE]
    result = object.__new__(WindowHead)
    result.bank, result.name = self, None
    result.origin = Value.encoded(self.names,
      (OR(*(AND(guard, head.origin.bits[bit]) for guard, head in cases))
       for bit in range((len(self.names) - 1).bit_length())),
      OR(*(guard for guard, _ in cases)))
    result.offset = Bits(tuple(OR(*(AND(guard, head.offset.bits[bit]) for guard, head in cases))
                              for bit in range(self.width + 2)))
    result.radius = max((head.radius for _, head in cases), default=0)
    return result

  def finalize(self):
    for head in self.heads.values():
      head.finalize()
    self.phase.store(self.circuit, self.phase_key)


def fixture(quantum=3):
  circuit = Circuit("abcdefgh")
  stream = WindowStream(circuit, ("x", "y"), 2 * quantum + 2)
  x, y = (stream.heads[name] for name in stream.names)
  char = circuit.input()
  for _ in range(quantum):
    x.move(1, AND(char.eq("a"), x.can_move(1)))
    x.move(-1, AND(char.eq("b"), x.can_move(-1)))
  y.copy_from(x, char.eq("c"))
  x.copy_from(y, char.eq("d"))
  y.move(1, AND(char.eq("e"), y.can_move(1)))
  y.move(-1, AND(char.eq("f"), y.can_move(-1)))
  observations = {}
  for head in (x, y):
    value = head.read(head.available())
    key = head.name + ".read"
    circuit.put(key, value, (*circuit.alphabet, None), None)
    observations[head.name] = key
  answer = x.read(x.available()).eq("a")
  stream.finalize()
  return circuit, stream, observations, circuit.machine(answer)
