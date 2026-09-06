"""Sparse real-time TM to plain PEG: inspect only explicitly guarded tapes.

Like tm2peg.py, one transition consumes one symbol of reverse(input).
This does NOT convert an offline instruction program into a real-time TM.
Absent tape operations preserve both focus and position. A None write
preserves focus while moving. Tape zippers are bi-infinite, as in tm2peg.
Overlapping partial guards are rejected instead of assigning hidden priority.
"""
from dataclasses import dataclass
import json


@dataclass
class Transition:
  source: str
  input: str
  focus: dict
  target: str
  operations: dict


class SymbolicTM:
  def __init__(self, ntapes, states, initial, accepting, transitions,
               input_alphabet="ab", blank="_"):
    self.ntapes, self.states = ntapes, tuple(states)
    self.initial, self.accepting = initial, set(accepting)
    self.transitions = tuple(transitions)
    self.input_alphabet, self.blank = tuple(input_alphabet), blank
    if ntapes < 0 or len(set(self.states)) != len(self.states):
      raise ValueError("invalid tapes or duplicate states")
    if initial not in self.states or not self.accepting <= set(self.states):
      raise ValueError("unknown initial/accepting state")
    if not self.input_alphabet or any(len(c) != 1 or ord(c) > 0xffff or
        0xd800 <= ord(c) <= 0xdfff for c in self.input_alphabet):
      raise ValueError("nonempty BMP scalar character alphabet required")
    symbols = {blank}
    groups = {}
    for row in self.transitions:
      if row.source not in self.states or row.target not in self.states:
        raise ValueError("unknown transition state")
      if row.input not in self.input_alphabet:
        raise ValueError("unknown input character")
      if any(t not in range(ntapes) for t in (*row.focus, *row.operations)):
        raise ValueError("unknown tape")
      symbols.update(row.focus.values())
      for write, move in row.operations.values():
        if move not in ("L", "R", "S"):
          raise ValueError("invalid move")
        if write is not None:
          symbols.add(write)
      peers = groups.setdefault((row.source, row.input), [])
      for previous in peers:
        if all(previous.focus[t] == row.focus[t]
               for t in previous.focus.keys() & row.focus.keys()):
          raise ValueError("overlapping symbolic transition guards")
      peers.append(row)
    self.tape_alphabet = tuple(sorted(symbols))
    self.groups = groups

  def run(self, word):
    state = self.initial
    tapes, heads = [{} for _ in range(self.ntapes)], [0] * self.ntapes
    for char in reversed(word):
      scanned = [t.get(h, self.blank) for t, h in zip(tapes, heads)]
      row = next((r for r in self.groups.get((state, char), ())
                  if all(scanned[t] == s for t, s in r.focus.items())), None)
      if row is None:
        return False
      state = row.target
      for t, (write, move) in row.operations.items():
        if write is not None:
          tapes[t][heads[t]] = write
        heads[t] += {"L": -1, "R": 1, "S": 0}[move]
    return state in self.accepting

  def compile(self):
    rules = []
    state_ids = {q: i for i, q in enumerate(self.states)}
    symbol_ids = {s: i for i, s in enumerate(self.tape_alphabet)}
    # Parser.CHAR accepts Unicode escapes but not JSON's backspace escape.
    literal = lambda text: '"\\u0008"' if text == "\b" else json.dumps(text, ensure_ascii=False)
    guard = lambda text: f"&({text})"
    previous = lambda text: ". " + text
    state = lambda q: f"St_{state_ids[q]}"
    focus = lambda t, s: f"Sc_{t}_{symbol_ids[s]}"
    pushed = lambda side, t, s: f"{side}sym_{t}_{symbol_ids[s]}"

    def add(name, alternatives):
      rules.append(f"{name} = " + (" / ".join(alternatives) or '!""') + ";")

    add("S", ["(" + (" / ".join(guard(state(q)) for q in self.states
                                 if q in self.accepting) or '!""') + ") " +
              "(" + " / ".join(literal(c) for c in self.input_alphabet) + ")* !."])
    # Each guard is shared by all state/tape equations. No focus-vector
    # Cartesian product is formed, including for unchanged tapes.
    for i, row in enumerate(self.transitions):
      parts = [guard(literal(row.input)), guard(previous(state(row.source)))]
      parts.extend(guard(previous(focus(t, s))) for t, s in sorted(row.focus.items()))
      add(f"D_{i}", [" ".join(parts)])
    for q in self.states:
      add(state(q), (["&(!.)"] if q == self.initial else []) +
          [f"D_{i}" for i, row in enumerate(self.transitions) if row.target == q])

    for t in range(self.ntapes):
      changed = []
      moves = {"L": [], "R": []}
      writes = {s: [] for s in self.tape_alphabet}
      pushes = {(side, s): [] for side in ("L", "R") for s in self.tape_alphabet}
      for i, row in enumerate(self.transitions):
        write, move = row.operations.get(t, (None, "S"))
        event = f"D_{i}"
        if move != "S":
          changed.append(event)
          moves[move].append(event)
          side = "L" if move == "R" else "R"
          for s in self.tape_alphabet:
            if write is None:
              pushes[side, s].append(event + " " + guard(previous(focus(t, s))))
            elif write == s:
              pushes[side, s].append(event)
        elif write is not None:
          changed.append(event)
          writes[write].append(event)
      add(f"Change_{t}", changed)
      for direction in ("L", "R"):
        add(f"Move{direction}_{t}", moves[direction])
      for s in self.tape_alphabet:
        alts = ["&(!.)"] if s == self.blank else []
        alts += writes[s]
        alts.append(f"!Change_{t} " + guard(previous(focus(t, s))))
        for side, direction in (("R", "R"), ("L", "L")):
          ptr = previous(f"{side}t_{t}")
          alts.append(f"Move{direction}_{t} " + guard(ptr + " " + pushed(side, t, s)))
          if s == self.blank:
            alts.append(f"Move{direction}_{t} !({ptr})")
        add(focus(t, s), alts)
      for side, push_direction, pop_direction in (("L", "R", "L"), ("R", "L", "R")):
        ptr = previous(f"{side}t_{t}")
        add(f"{side}t_{t}", [f"Move{push_direction}_{t}",
            f"Move{pop_direction}_{t} {ptr} {ptr}",
            f"!MoveL_{t} !MoveR_{t} {ptr}"])
        for s in self.tape_alphabet:
          add(pushed(side, t, s), pushes[side, s])
    return "\n".join(rules) + "\n"


def from_dense(machine, extra_tapes=0):
  rows = [Transition(q, char, dict(enumerate(scanned)), q2,
                     dict(enumerate(operations)))
          for (q, scanned, char), (q2, operations) in machine.delta.items()]
  return SymbolicTM(machine.ntapes + extra_tapes, machine.states, machine.initial,
                    machine.accepting, rows, machine.input_alphabet, machine.blank)
