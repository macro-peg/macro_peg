"""Local-head version of the GS overlap pass.

The generator's control observes symbols and head order only. Coordinates
live in the VM below, never in the generator. Its only integer local is a
phase bounded by the fixed parameter k. Compilation closes over that finite
control and emits a table, so the generator is not a runtime primitive.
"""
from dataclasses import dataclass
from types import GeneratorType


HEADS = ("Origin", "OriginalEnd", "End", "Cut", "Tail", "A", "B", "P",
         "First", "Reach", "Walk", "KP", "KFirst", "Second")
BLIND = frozenset(("KP", "KFirst", "Second"))
TESTS = frozenset(("equal", "less", "symbols"))


def _initialize(k):
  yield ("copy", "A", "Cut")
  yield ("copy", "P", "Cut")
  yield ("move", (("P", 1),))
  yield ("copy", "B", "P")
  yield ("copy", "KP", "Cut")
  yield ("move", (("KP", k),))


def _reset_shift(k, search):
  phase = 0
  nonempty = not (yield ("equal", "A", "Cut"))
  while not (yield ("equal", "A", "Cut")):
    if search:
      yield ("move", (("A", -1), ("B", -1)))
    else:
      yield ("move", (("A", -1),))
    phase += 1
    if phase == k:
      if search:
        yield ("move", (("P", 1), ("B", 1), ("KP", -1)))
      else:
        yield ("move", (("P", 1), ("KP", k)))
      phase = 0
  if not nonempty or phase:
    if search:
      yield ("move", (("P", 1), ("B", 1), ("KP", -1)))
    else:
      yield ("move", (("P", 1), ("KP", k)))
  if not search:
    yield ("copy", "B", "P")


def _period_shift(k, search):
  yield ("copy", "Walk", "Cut")
  while (yield ("less", "Walk", "First")):
    if search:
      yield ("move", (("Walk", 1), ("A", -1), ("P", 1), ("KP", -1)))
    else:
      yield ("move", (("Walk", 1), ("A", -1), ("P", 1), ("KP", k)))


def _first(k, bounded):
  yield from _initialize(k)
  while (yield ("less", "P", "End")):
    if bounded and not (yield ("less", "P", "Second")):
      break
    while (yield ("less", "B", "End")):
      if not (yield ("less", "B", "KP")):
        break
      if not (yield ("symbols", "A", "B")):
        break
      yield ("move", (("A", 1), ("B", 1)))
    if (yield ("equal", "B", "KP")):
      return True
    yield from _reset_shift(k, False)
  return False


def _second(k):
  yield from _initialize(k)
  while (yield ("less", "P", "End")):
    while (yield ("less", "B", "End")):
      if not (yield ("symbols", "A", "B")):
        break
      yield ("move", (("A", 1), ("B", 1)))
      if (yield ("less", "Reach", "B")) and not (yield ("less", "B", "KP")):
        return True
    if not (yield ("less", "A", "KFirst")) and not (yield ("less", "Reach", "A")):
      yield from _period_shift(k, False)
    else:
      yield from _reset_shift(k, False)
  return False


def _decompose(k):
  yield ("copy", "Cut", "Origin")
  while True:
    if not (yield from _first(k, False)):
      return False
    yield ("copy", "First", "P")
    yield ("copy", "KFirst", "B")
    while (yield ("less", "B", "End")):
      if not (yield ("symbols", "A", "B")):
        break
      yield ("move", (("A", 1), ("B", 1)))
    yield ("copy", "Reach", "B")
    if not (yield from _second(k)):
      return True
    yield ("copy", "Second", "P")
    while (yield from _first(k, True)):
      # Second remains at Cut + the saved second-period length. A copy of
      # Cut alone would lose that relative bound, so move both in lockstep.
      while (yield ("less", "Cut", "P")):
        yield ("move", (("Cut", 1), ("Second", 1)))


def _report(flags):
  if not flags:
    yield ("border", "KP")
    return False
  if (yield ("less", "KP", "Lower")):
    return True
  if not (yield ("less", "Cursor", "KP")):
    while (yield ("less", "KP", "Cursor")):
      yield ("flag", False)
      yield ("move", (("Cursor", -1),))
    yield ("flag", True)
    yield ("move", (("Cursor", -1),))
  return (yield ("less", "Cursor", "Lower"))


def _finish_flags():
  while not (yield ("less", "Cursor", "Lower")):
    if (yield ("equal", "Cursor", "Origin")):
      yield ("flag", True)
    else:
      yield ("flag", False)
    yield ("move", (("Cursor", -1),))


def border_controller(k=8, flags=False, tail_origin="Origin"):
  if type(k) is not int or k < 4:
    raise ValueError("fixed integer k >= 4 required")
  first_stage = True
  yield ("copy", "End", "OriginalEnd")
  yield ("copy", "Tail", tail_origin)
  while (yield ("less", "Origin", "End")):
    if flags and tail_origin != "Origin" and (yield ("less", "End", "Lower")):
      yield from _finish_flags()
      return
    period_exists = yield from _decompose(k)
    # During matching, KP is the current overlap length, and Second is
    # the last permitted text start (OriginalEnd - max(1, 2*Cut)).
    yield ("copy", "P", "Tail")
    yield ("copy", "KP", "End")
    if first_stage:
      yield ("move", (("P", 1), ("KP", -1)))
    yield ("copy", "A", "Cut")
    yield ("copy", "B", "P")
    yield ("copy", "Second", "OriginalEnd")
    yield ("copy", "Walk", "Origin")
    while (yield ("less", "Walk", "Cut")):
      yield ("move", (("Walk", 1), ("B", 1), ("Second", -2)))
    if (yield ("equal", "Cut", "Origin")):
      yield ("move", (("Second", -1),))
    while not (yield ("less", "Second", "P")):
      while (yield ("less", "B", "OriginalEnd")):
        if not (yield ("symbols", "A", "B")):
          break
        yield ("move", (("A", 1), ("B", 1)))
      if (yield ("equal", "B", "OriginalEnd")):
        # B can be reused for the prefix check, since its saved position
        # here is exactly OriginalEnd. A retains the matched suffix length.
        yield ("copy", "Walk", "Origin")
        yield ("copy", "B", "P")
        while (yield ("less", "Walk", "Cut")):
          if not (yield ("symbols", "Walk", "B")):
            break
          yield ("move", (("Walk", 1), ("B", 1)))
        if (yield ("equal", "Walk", "Cut")):
          if (yield from _report(flags)):
            yield from _finish_flags()
            return
        yield ("copy", "B", "OriginalEnd")
      if period_exists and not (yield ("less", "A", "KFirst")) and \
          not (yield ("less", "Reach", "A")):
        yield from _period_shift(k, True)
      else:
        yield from _reset_shift(k, True)
    # Retain only lengths below the completed interval. Tail = N - End.
    yield ("copy", "Second", "Origin")
    yield ("copy", "Walk", "Origin")
    while (yield ("less", "Walk", "Cut")):
      yield ("move", (("Walk", 1), ("Second", 2)))
    if not (yield ("equal", "Cut", "Origin")):
      yield ("move", (("Second", -1),))
    while (yield ("less", "Second", "End")):
      yield ("move", (("End", -1), ("Tail", 1)))
    first_stage = False
  if flags:
    yield from _finish_flags()


def _freeze(value):
  if value is None or type(value) in (bool, int, str):
    return value
  if type(value) is tuple:
    return tuple(_freeze(item) for item in value)
  raise ValueError(f"non-finite controller local type: {type(value).__name__}")


def _control_key(generator, event):
  frames = []
  while generator is not None:
    if not isinstance(generator, GeneratorType) or generator.gi_frame is None:
      raise ValueError("unsupported controller suspension")
    frame = generator.gi_frame
    frames.append((generator.gi_code.co_name, frame.f_lasti,
                   tuple((name, _freeze(value)) for name, value in sorted(frame.f_locals.items()))))
    generator = generator.gi_yieldfrom
  return event, tuple(frames)


@dataclass(frozen=True)
class Program:
  # Each row is (event, successors). Test successors are ordered false/true.
  code: tuple
  start: int
  k: int
  construction_states: int = 0


def minimize(program):
  """Bisimulation of the complete finite command/test table, without traces."""
  groups = [0] * len(program.code)
  while True:
    identifiers, refined = {}, []
    for event, targets in program.code:
      key = event, tuple(groups[target] for target in targets)
      refined.append(identifiers.setdefault(key, len(identifiers)))
    if refined == groups:
      break
    groups = refined
  code = [None] * len(set(groups))
  for index, (event, targets) in enumerate(program.code):
    code[groups[index]] = event, tuple(groups[target] for target in targets)
  return Program(tuple(code), groups[program.start], program.k,
                 program.construction_states or len(program.code))


def unit_moves(program):
  """Replace each bounded batch by one head's unit move per instruction."""
  code = list(program.code)
  for state, (event, targets) in enumerate(program.code):
    if event[0] != "move":
      continue
    moves = [(head, 1 if delta > 0 else -1)
             for head, delta in event[1] for _ in range(abs(delta))]
    if not moves:
      raise ValueError("empty move instruction")
    target = targets[0]
    for movement in reversed(moves[1:]):
      code.append((("move", (movement,)), (target,)))
      target = len(code) - 1
    code[state] = (("move", (moves[0],)), (target,))
  return minimize(Program(tuple(code), program.start, program.k, program.construction_states))


def compile_controller(k=8, *, max_states=10000, reduce=True, controller=border_controller,
                       tests=TESTS):
  """Close all finite-control branches, independently of any input words.

  max_states is a compiler watchdog. It cannot affect an emitted program's
  accepted word lengths. No sampled execution states determine this table.
  """
  def replay(path):
    generator = controller(k)
    event = next(generator)
    try:
      for response in path:
        event = generator.send(response)
    except StopIteration:
      generator.close()
      return None, None
    return generator, event

  generator, event = replay(())
  first_key = _control_key(generator, event)
  generator.close()
  identifiers = {first_key: 1}
  code, paths = [(("halt",), ()), None], [None, ()]
  cursor = 1
  while cursor < len(code):
    path = paths[cursor]
    generator, event = replay(path)
    generator.close()
    destinations = []
    for response in ((False, True) if event[0] in tests else (None,)):
      follow = (*path, response)
      generator, following = replay(follow)
      if generator is None:
        destination = 0
      else:
        key = _control_key(generator, following)
        generator.close()
        if key not in identifiers:
          if len(code) >= max_states:
            raise RuntimeError("finite controller construction exceeded its watchdog")
          identifiers[key] = len(code)
          code.append(None)
          paths.append(follow)
        destination = identifiers[key]
      destinations.append(destination)
    code[cursor] = event, tuple(destinations)
    cursor += 1
  program = Program(tuple(code), 1, k, len(code))
  return minimize(program) if reduce else program


class HeadVM:
  """Observer/interpreter for the explicit finite table and preloaded word."""
  def __init__(self, word, program):
    self.word, self.program = word, program
    self.positions = dict.fromkeys(HEADS, 0)
    self.positions["OriginalEnd"] = len(word)
    self.state = program.start
    self.steps = self.motion = self.comparisons = 0
    self.outputs = []

  @property
  def done(self):
    return self.program.code[self.state][0][0] == "halt"

  def step(self):
    if self.done:
      raise RuntimeError("local-head controller already halted")
    event, targets = self.program.code[self.state]
    op, *args = event
    self.steps += 1
    decision = None
    if op == "move":
      for head, delta in args[0]:
        self.positions[head] += delta
        self.motion += abs(delta)
        if head not in BLIND and self.positions[head] < 0:
          raise AssertionError(("left-end crossing", head, event))
    elif op == "copy":
      if args[0] not in BLIND and args[1] in BLIND:
        raise AssertionError("cannot restore a data head from a blind coordinate")
      self.positions[args[0]] = self.positions[args[1]]
    elif op in TESTS:
      left, right = (self.positions[head] for head in args)
      self.comparisons += 1
      if op == "equal":
        decision = left == right
      elif op == "less":
        decision = left < right
      else:
        if any(head in BLIND for head in args) or not (0 <= left < len(self.word) and 0 <= right < len(self.word)):
          raise AssertionError(("invalid local symbol query", event, left, right, len(self.word)))
        decision = self.word[left] == self.word[right]
    elif op == "border":
      # Coordinates are decoded only by the observer; control never receives
      # this output integer. A local consumer may compare the named head.
      self.outputs.append(self.positions[args[0]])
    else:
      raise ValueError("unknown local-head instruction")
    self.state = targets[int(decision)] if op in TESTS else targets[0]

  def run(self, watchdog=None):
    # A test watchdog, not a semantic input cap or a real-time claim.
    if watchdog is None:
      watchdog = 10000 * (len(self.word) + 1)
    while not self.done:
      if self.steps >= watchdog:
        raise RuntimeError("local-head test watchdog exceeded")
      self.step()
    return tuple(self.outputs)
