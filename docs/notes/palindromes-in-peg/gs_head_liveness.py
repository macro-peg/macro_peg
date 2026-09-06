"""Exact backward liveness for the finite program's head differences.

Only differences needed by a subsequent test/output are retained. Copying a
head substitutes its source in those future differences. No input traces or
numeric positions participate in this analysis.
"""
from dataclasses import dataclass
from itertools import count

from gs_heads import HEADS


@dataclass(frozen=True)
class Liveness:
  names: tuple
  before: tuple
  after: tuple
  colors: dict

  @property
  def registers(self):
    return max(self.colors.values(), default=-1) + 1

  def canonical(self, left, right):
    if left == right:
      return None, 1
    if self.names.index(left) < self.names.index(right):
      return (left, right), 1
    return (right, left), -1


def analyze(program, names=HEADS, *, observe_positions=True, availability_distance=True):
  names = tuple(names)
  rank = {name: i for i, name in enumerate(names)}
  if len(rank) != len(names):
    raise ValueError("distinct head names required")

  def pair(left, right):
    if left not in rank or right not in rank:
      raise ValueError("unknown head in finite program")
    if left == right:
      return None
    return (left, right) if rank[left] < rank[right] else (right, left)

  before = [set() for _ in program.code]
  changed = True
  while changed:
    changed = False
    for state in reversed(range(len(program.code))):
      event, targets = program.code[state]
      live = set().union(*(before[target] for target in targets))
      if event[0] == "copy":
        _, target, source = event
        live = {pair(source if left == target else left, source if right == target else right)
                for left, right in live}
      if event[0] in ("less", "equal", "assert_equal"):
        live.add(pair(*event[1:]))
      elif observe_positions and event[0] in ("border", "match"):
        live.add(pair("Origin", event[1]))
      elif availability_distance and event[0] == "available":
        live.add(pair(event[1], "OriginalEnd"))
      live.discard(None)
      if live != before[state]:
        before[state] = live
        changed = True
  after = [frozenset().union(*(before[target] for target in targets))
           for _, targets in program.code]
  graph = {p: set() for live in before for p in live}
  # The union of successors matters at branching instructions: simultaneous
  # counter updates must not alias values needed by either successor.
  for live in (*before, *after):
    for p in live:
      graph[p].update(live - {p})
  colors = {}
  while len(colors) != len(graph):
    chosen = max((p for p in graph if p not in colors),
                 key=lambda p: (len({colors[q] for q in graph[p] if q in colors}),
                                len(graph[p]), rank[p[0]], rank[p[1]]))
    occupied = {colors[q] for q in graph[chosen] if q in colors}
    colors[chosen] = next(c for c in count() if c not in occupied)
  return Liveness(names, tuple(map(frozenset, before)), tuple(after), colors)


@dataclass(frozen=True)
class ReaderLiveness:
  before: tuple
  after: tuple
  colors: dict

  @property
  def registers(self):
    return max(self.colors.values(), default=-1) + 1


def analyze_readers(program):
  before = [set() for _ in program.code]
  changed = True
  while changed:
    changed = False
    for state in reversed(range(len(program.code))):
      event, targets = program.code[state]
      live = set().union(*(before[target] for target in targets))
      if event[0] == "copy" and event[1] in live:
        live.remove(event[1])
        live.add(event[2])
      if event[0] == "symbols":
        live.update(event[1:])
      elif event[0] == "available":
        live.add(event[1])
      if live != before[state]:
        before[state] = live
        changed = True
  after = [frozenset().union(*(before[target] for target in targets))
           for _, targets in program.code]
  graph = {head: set() for live in before for head in live}
  for live in (*before, *after):
    for head in live:
      graph[head].update(live - {head})
  colors = {}
  while len(colors) != len(graph):
    chosen = max((head for head in graph if head not in colors),
                 key=lambda head: (len({colors[other] for other in graph[head] if other in colors}),
                                   len(graph[head]), head))
    occupied = {colors[other] for other in graph[chosen] if other in colors}
    colors[chosen] = next(c for c in count() if c not in occupied)
  return ReaderLiveness(tuple(map(frozenset, before)), tuple(after), colors)
