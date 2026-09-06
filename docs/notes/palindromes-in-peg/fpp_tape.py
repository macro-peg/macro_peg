"""Fischer–Paterson Algorithm Y, with local heads and unary counters.

Source: String-Matching and Other Products, MIT MAC TM-41 (1974),
section 3, printed pp. 6–11. This is an executable multihead-tape
experiment, NOT a single-head TM transition table or a PAL PEG.

The array P stores border length minus one, with P(-1) = P(0) = -1.
Instead of storing P, Y holds 0 1^delta(-1) 0 1^delta(0) ...,
where delta(i) = 1 + P(i) - P(i+1). Its total size is linear.
The two Y heads share an append-only tape. No head address is read by
the algorithm; the final A address is decoded only by the result observer.
All reads, writes and unit moves are charged, including unary-counter
copy/reset work. Input loading is supplied offline, not charged here.
"""
from dataclasses import dataclass


@dataclass
class Result:
  border: int
  operations: int
  borders: tuple = ()


LEFT, END, BLANK = object(), object(), object()


class Clock:
  def __init__(self):
    self.operations = 0


class Head:
  """VM implementation only: program control sees symbols, never positions."""
  def __init__(self, tape, clock, position=0):
    self._tape = tape
    self._position = position
    self.clock = clock

  def read(self):
    self.clock.operations += 1
    return self._tape.get(self._position, BLANK)

  def write(self, symbol):
    self.clock.operations += 1
    self._tape[self._position] = symbol

  def move(self, direction):
    assert direction in (-1, 1)
    self.clock.operations += 1
    self._position += direction
    assert self._position >= 0


class Unary:
  def __init__(self, clock):
    self.head = Head({0: LEFT}, clock)

  def nonzero(self):
    return self.head.read() != LEFT

  def push(self):
    self.head.move(1)
    self.head.write(1)

  def pop(self):
    assert self.nonzero()
    self.head.write(BLANK)
    self.head.move(-1)

  def copy_to_empty(self, target):
    assert not target.nonzero()
    # Read backwards without erasing S; T's write head stays at its top.
    while self.head.read() != LEFT:
      target.push()
      self.head.move(-1)
    # Restore S's head by scanning to the first blank and stepping back.
    self.head.move(1)
    while self.head.read() != BLANK:
      self.head.move(1)
    self.head.move(-1)


class TapeQueue:
  """Two independent single-head stacks; each entry transfers at most once."""
  def __init__(self, clock):
    self.back = Head({0: LEFT}, clock)
    self.front = Head({0: LEFT}, clock)

  def append(self, symbol):
    self.back.move(1)
    self.back.write(symbol)

  def take(self):
    if self.front.read() == LEFT:
      while self.back.read() != LEFT:
        symbol = self.back.read()
        self.back.write(BLANK)
        self.back.move(-1)
        self.front.move(1)
        self.front.write(symbol)
    symbol = self.front.read()
    assert symbol != LEFT, "reader overtook the delta writer"
    self.front.write(BLANK)
    self.front.move(-1)
    return symbol


class DeltaReader:
  """Materialize the append stream only on first visiting a blank cell."""
  def __init__(self, clock, queue):
    self.head = Head({0: 0, 1: 1, 2: 0}, clock)
    self.queue = queue

  def move(self, direction):
    self.head.move(direction)

  def read(self):
    symbol = self.head.read()
    if symbol == BLANK:
      symbol = self.queue.take()
      self.head.write(symbol)
    return symbol


def border_machine(word, collect_chain=False, *, _single_head=False):
  clock = Clock()
  z = dict(enumerate([LEFT, *word, END]))
  a = Head(z, clock)                    # p = -1
  b = Head(dict(z) if _single_head else z, clock, 1)  # i = 0
  if _single_head:
    queue = TapeQueue(clock)
    c = DeltaReader(clock, queue)
    append = queue.append
  else:
    y = {0: 0, 1: 1, 2: 0}            # delta(-1) = 1; pending d = 0
    c = Head(y, clock)
    d = Head(y, clock, 2)
    def append(symbol):
      d.move(1)
      d.write(symbol)
  s, t = Unary(clock), Unary(clock)
  if b.read() == END:
    return Result(0, clock.operations)

  while True:
    b.move(1)
    symbol = b.read()
    if symbol == END:
      break
    while True:
      a.move(1)
      match = a.read() == symbol
      if match:
        # Finalize delta(i) = d; carry s forward through delta(p).
        append(0)
        c.move(1)
        while c.read() == 1:
          s.push()
          c.move(1)
        break
      a.move(-1)
      if a.read() == LEFT:
        # Finalize delta(i) = d + 1. p and s remain -1 and 0.
        append(1)
        append(0)
        break
      # Fallback by OLD s zeros, subtracting intervening unary deltas
      # from S. Copying S is essential: S changes during the traversal.
      s.copy_to_empty(t)
      while t.nonzero():
        c.move(-1)
        while c.read() == 1:
          s.pop()
          c.move(-1)
        a.move(-1)
        append(1)
        t.pop()

  # Observer only: a finite machine would return A positioned at the border.
  longest = a._position
  borders = []
  if collect_chain:
    while a.read() != LEFT:
      borders.append(a._position)
      s.copy_to_empty(t)
      while t.nonzero():
        c.move(-1)
        while c.read() == 1:
          s.pop()
          c.move(-1)
        a.move(-1)
        t.pop()
  return Result(longest, clock.operations, tuple(borders))


def single_head_border_machine(word, collect_chain=False):
  """Seven active single-head tapes; linear total local work, not real time.

  Two offline copies of the read-only input replace its two read heads.
  A materialized delta prefix plus two queue stacks replaces the shared
  append tape. The other two tapes are the original unary counters.
  """
  return border_machine(word, collect_chain, _single_head=True)


def initial_palindromes(word, *, single_head=False):
  """Nonempty palindrome prefix lengths, decreasing (offline FPP).

  Preparation of word # reverse(word) is linear but external to the
  counted kernel. The fresh symbol cannot occur in word. Output integers
  are observer-decoded head positions, not machine registers.
  """
  symbols = list(word)
  machine = single_head_border_machine if single_head else border_machine
  return machine([*symbols, object(), *reversed(symbols)], True)
