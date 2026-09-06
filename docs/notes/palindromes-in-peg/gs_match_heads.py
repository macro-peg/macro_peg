"""Fixed-head GS matching, including the short-prefix verifier.

Tail is the fixed boundary between the pattern and growing text. OriginalEnd
is the arrival frontier. The control is finite; integer positions live only
in StreamingMatcher, the test interpreter. InputHeads remain to be attached.
"""
from gs_heads import (HEADS, BLIND, TESTS, _decompose, _period_shift, _reset_shift,
                      compile_controller, unit_moves)


MATCH_HEADS = (*HEADS, "U")
MATCH_TESTS = TESTS | {"available"}


def matcher_controller(k=8):
  if type(k) is not int or k < 4:
    raise ValueError("fixed integer k >= 4 required")
  yield ("copy", "End", "Tail")
  period_exists = yield from _decompose(k)
  yield ("copy", "P", "Tail")
  yield ("copy", "KP", "End")
  yield ("copy", "A", "Cut")
  yield ("copy", "B", "P")
  yield ("copy", "Walk", "Origin")
  while (yield ("less", "Walk", "Cut")):
    # This initial text offset must consume arrived cells too. The indexed
    # reference can name a future position; a local head must wait before
    # crossing each of those characters.
    while not (yield ("available", "B")):
      pass
    yield ("move", (("Walk", 1), ("B", 1)))
  while True:
    yield ("copy", "Walk", "Origin")
    yield ("copy", "U", "P")
    prefix_ok = True
    while True:
      while not (yield ("available", "B")):
        pass
      if not (yield ("symbols", "A", "B")):
        break
      yield ("move", (("A", 1), ("B", 1)))
      for phase in range(2):
        if not prefix_ok or not (yield ("less", "Walk", "Cut")):
          break
        if not (yield ("symbols", "Walk", "U")):
          prefix_ok = False
          break
        yield ("move", (("Walk", 1), ("U", 1)))
      if (yield ("equal", "A", "End")):
        if prefix_ok:
          yield ("assert_equal", "Walk", "Cut")
          yield ("match", "B")
        break
    if period_exists and not (yield ("less", "A", "KFirst")) and \
        not (yield ("less", "Reach", "A")):
      yield from _period_shift(k, True)
    else:
      yield from _reset_shift(k, True)


def compile_matcher(k=8, *, unit=True):
  program = compile_controller(k, controller=matcher_controller, tests=MATCH_TESTS)
  return unit_moves(program) if unit else program


class StreamingMatcher:
  """Position-array observer for the finite head table, without a clock claim."""
  def __init__(self, pattern, program=None):
    if not pattern:
      raise ValueError("nonempty pattern required")
    self.word = list(pattern)
    self.pattern_size = len(pattern)
    self.program = compile_matcher() if program is None else program
    self.positions = dict.fromkeys(MATCH_HEADS, 0)
    self.positions["Tail"] = self.positions["OriginalEnd"] = len(pattern)
    self.state = self.program.start
    self.steps = 0
    self.outputs = []

  @property
  def waiting(self):
    event = self.program.code[self.state][0]
    return event[0] == "available" and self.positions[event[1]] >= len(self.word)

  def append(self, char):
    self.word.append(char)
    self.positions["OriginalEnd"] += 1

  def step(self):
    event, targets = self.program.code[self.state]
    op, *args = event
    self.steps += 1
    decision, output = None, None
    if op == "move":
      for head, delta in args[0]:
        self.positions[head] += delta
        if head not in BLIND and not 0 <= self.positions[head] <= len(self.word):
          raise AssertionError(("head crossed the arrived input", head, self.positions[head], len(self.word)))
    elif op == "copy":
      self.positions[args[0]] = self.positions[args[1]]
    elif op in ("equal", "less", "assert_equal", "symbols"):
      a, b = (self.positions[head] for head in args)
      if op == "symbols":
        if not (0 <= a < len(self.word) and 0 <= b < len(self.word)):
          raise AssertionError(("query beyond arrival frontier", event, a, b, len(self.word)))
        decision = self.word[a] == self.word[b]
      elif op == "assert_equal":
        if a != b:
          raise AssertionError("short-prefix checker missed its deadline")
      else:
        decision = a == b if op == "equal" else a < b
    elif op == "available":
      decision = self.positions[args[0]] < len(self.word)
    elif op == "match":
      output = self.positions[args[0]] - self.pattern_size
      self.outputs.append(output)
    else:
      raise ValueError("unknown matching instruction")
    self.state = targets[int(decision)] if op in MATCH_TESTS else targets[0]
    return output

  def drain(self, watchdog=1000000):
    start = self.steps
    while not self.waiting:
      if self.steps - start >= watchdog:
        raise RuntimeError("test interpreter exceeded its watchdog")
      self.step()
    return self.steps - start
