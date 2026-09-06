"""Experimental inverse expansion for the plain PEG subset emitted by tm2peg.

inverse_repeat(G, k) recognizes {w | G recognizes each character of w k times}.
The virtual offset inside a repeated character is a finite grammar parameter,
not runtime state. E_i_j succeeds exactly when E returns in phase j from i.
An ordered-choice fallback is guarded by failure of E in *every* return phase.

This is an output-boundary construction, not a real-time scheduling proof.
Input is BMP scalar text. The parser deliberately supports only double-quoted
literals, '.', names, sequence, choice, predicates, grouping and '*'. Source
grammars must terminate (in particular no nullable repetition/left recursion).
"""
import json
import re
from array import array


EMPTY = ("empty",)
TOKEN = re.compile(r"[A-Za-z_][A-Za-z_0-9]*|[=;()/!&.*]")


class CompactMemo:
  """Sparse rows become integer arrays when packrat cells grow dense."""
  def __init__(self, length):
    self.rows, self.width = {}, length + 1
    self.kind, self.missing = ("H", 65535) if length < 65533 else ("Q", 2**64 - 1)
    self.threshold = max(8, self.width // (32 if self.kind == "H" else 8))

  def get(self, key, default=-1):
    identity, position = key
    row = self.rows.get(identity)
    if row is None: return default
    value = row.get(position, self.missing) if isinstance(row, dict) else row[position]
    return -1 if value == self.missing else -2 if value == self.missing - 1 else value

  def __setitem__(self, key, value):
    identity, position = key
    row = self.rows.get(identity)
    if row is None:
      row = self.rows[identity] = {}
    row[position] = self.missing + value + 1 if value < 0 else value
    if isinstance(row, dict) and len(row) > self.threshold:
      dense = array(self.kind, [self.missing]) * self.width
      for index, stored in row.items(): dense[index] = stored
      self.rows[identity] = dense


class Grammar:
  def __init__(self, source, start="S"):
    self.tokens = []
    cursor = 0
    decoder = json.JSONDecoder()
    while cursor < len(source):
      if source[cursor].isspace():
        cursor += 1
      elif source[cursor] == '"':
        value, end = decoder.raw_decode(source, cursor)
        if any(ord(c) > 0xffff or 0xd800 <= ord(c) <= 0xdfff for c in value):
          raise ValueError("BMP scalar literals required")
        self.tokens.append(("literal", value))
        cursor = end
      else:
        found = TOKEN.match(source, cursor)
        if not found:
          raise ValueError(f"unsupported PEG syntax at {cursor}")
        self.tokens.append(found[0])
        cursor += len(found[0])
    self.cursor, self.rules = 0, {}
    while self.cursor < len(self.tokens):
      name = self.take()
      if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", name):
        raise ValueError("rule name required")
      if name in self.rules:
        raise ValueError("duplicate rule")
      self.expect("=")
      expr = self.choice()
      self.expect(";")
      self.rules[name] = expr
    if not self.rules:
      raise ValueError("empty grammar")
    if start not in self.rules:
      raise ValueError(f"unknown start rule: {start}")
    self.start = start

  def peek(self):
    return self.tokens[self.cursor] if self.cursor < len(self.tokens) else None

  def take(self):
    result = self.peek()
    if result is None:
      raise ValueError("unexpected end of grammar")
    self.cursor += 1
    return result

  def expect(self, token):
    if self.take() != token:
      raise ValueError(f"expected {token}")

  def choice(self):
    result = self.sequence()
    while self.peek() == "/":
      self.take()
      result = ("choice", result, self.sequence())
    return result

  def sequence(self):
    items = []
    while self.peek() is not None and self.peek() not in ("/", ")", ";"):
      items.append(self.prefix())
    if not items:
      raise ValueError('use "" for an empty expression')
    result = items[0]
    for expr in items[1:]:
      result = ("seq", result, expr)
    return result

  def prefix(self):
    if self.peek() in ("!", "&"):
      return (self.take(), self.prefix())
    token = self.take()
    if token == "(":
      result = self.choice()
      self.expect(")")
    elif token == ".":
      result = ("any",)
    elif isinstance(token, tuple):
      result = EMPTY
      for c in token[1]:
        char = ("char", c)
        result = char if result == EMPTY else ("seq", result, char)
    elif re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", token):
      result = ("ref", token)
    else:
      raise ValueError(f"unexpected {token}")
    while self.peek() == "*":
      self.take()
      result = ("star", result)
    return result

  def accepts(self, word, compact=False):
    return self.parse_prefix(word, compact) == len(word)

  def parse_prefix(self, word, compact=False):
    """Return the start rule's end position, or None on failure.

    Pointer-like PEG rules deliberately leave a suffix. Exposing their result
    lets construction experiments check that suffix, rather than only checking
    whole-word acceptance.
    """
    if any(ord(c) > 0xffff or 0xd800 <= ord(c) <= 0xdfff for c in word):
      raise ValueError("BMP scalar input required")
    memo = CompactMemo(len(word)) if compact else {}
    # Explicit continuations avoid a host recursion cap on a valid generated
    # grammar. Memo keys use AST identity; rule bodies themselves are shared.
    work = [("eval", self.rules[self.start], 0)]
    out = None
    while work:
      task = work.pop()
      action = task[0]
      if action == "save":
        memo[task[1]] = 0 if out is None else out + 1
      elif action == "seq":
        if out is not None: work.append(("eval", task[1], out))
      elif action == "choice":
        if out is None: work.append(("eval", task[1], task[2]))
      elif action == "predicate":
        out = task[2] if (out is not None) == (task[1] == "&") else None
      elif action == "repeat":
        if out is None:
          out = task[2]
        elif out == task[2]:
          raise ValueError("nullable repetition")
        else:
          work.append(("repeat", task[1], out))
          work.append(("eval", task[1], out))
      else:
        expr, pos = task[1], task[2]
        key = (id(expr), pos)
        cached = memo.get(key, -1)
        if cached >= 0:
          out = None if cached == 0 else cached - 1
          continue
        if cached == -2:
          raise ValueError("non-consuming recursion")
        memo[key] = -2
        work.append(("save", key))
        kind = expr[0]
        if kind == "empty":
          out = pos
        elif kind in ("char", "any"):
          out = pos + 1 if pos < len(word) and (kind == "any" or word[pos] == expr[1]) else None
        elif kind == "ref":
          work.append(("eval", self.rules[expr[1]], pos))
        elif kind in ("seq", "choice"):
          work.append((kind, expr[2], pos))
          work.append(("eval", expr[1], pos))
        elif kind in ("!", "&"):
          work.append(("predicate", kind, pos))
          work.append(("eval", expr[1], pos))
        elif kind == "star":
          work.append(("repeat", expr[1], pos))
          work.append(("eval", expr[1], pos))
        else:
          raise ValueError(kind)
    return out



def inverse_repeat(source, width, start="S"):
  if type(width) is not int or width < 1:
    raise ValueError("positive integer width required")
  grammar = Grammar(source, start)
  nodes, ids = [], {}

  def register(expr):
    if expr not in ids:
      ids[expr] = len(nodes)
      nodes.append(expr)
      if expr[0] == "ref":
        register(grammar.rules[expr[1]])
      elif expr[0] in ("seq", "choice", "!", "&", "star"):
        for child in expr[1:]:
          register(child)

  register(grammar.rules[grammar.start])
  # Prune impossible phase returns before rendering. Besides reducing size,
  # this keeps a failed E_i_i from masquerading as a nullable prefix in the
  # target grammar's conservative left-recursion check.
  returns = {expr: set() for expr in nodes}
  diagonal = {(i, i) for i in range(width)}
  changed = True
  while changed:
    changed = False
    for expr in nodes:
      kind = expr[0]
      if kind in ("empty", "!"):
        possible = diagonal
      elif kind in ("char", "any"):
        possible = {(i, (i + 1) % width) for i in range(width)}
      elif kind == "ref":
        possible = returns[grammar.rules[expr[1]]]
      elif kind == "choice":
        possible = returns[expr[1]] | returns[expr[2]]
      elif kind == "seq":
        possible = {(i, j) for i, k in returns[expr[1]]
                    for middle, j in returns[expr[2]] if middle == k}
      elif kind == "&":
        possible = {(i, i) for i, _ in returns[expr[1]]}
      elif kind == "star":
        possible = diagonal | {(i, j) for i, k in returns[expr[1]]
                               for middle, j in returns[expr] if middle == k}
      if not possible <= returns[expr]:
        returns[expr] |= possible
        changed = True
  name = lambda expr, i, j: f"E_{ids[expr]}_{i}_{j}"
  success = lambda expr, i: f"Y_{ids[expr]}_{i}"
  fail = '!""'
  lines = [f"S = {name(grammar.rules[grammar.start], 0, 0)} !.;"]
  literal = lambda c: '"\\u0008"' if c == "\b" else json.dumps(c, ensure_ascii=False)
  for expr in nodes:
    kind = expr[0]
    for i in range(width):
      choices = [f"&{name(expr, i, j)}" for j in range(width) if (i, j) in returns[expr]]
      lines.append(success(expr, i) + " = " + (" / ".join(choices) or fail) + ";")
      for j in range(width):
        alternatives = []
        if (i, j) not in returns[expr]:
          pass
        elif kind == "empty" and i == j:
          alternatives = ['""']
        elif kind in ("char", "any") and j == (i + 1) % width:
          terminal = literal(expr[1]) if kind == "char" else "."
          alternatives = [terminal if i == width - 1 else f"&({terminal})"]
        elif kind == "ref":
          alternatives = [name(grammar.rules[expr[1]], i, j)]
        elif kind == "seq":
          alternatives = [name(expr[1], i, k) + " " + name(expr[2], k, j)
                          for k in range(width)
                          if (i, k) in returns[expr[1]] and (k, j) in returns[expr[2]]]
        elif kind == "choice":
          if (i, j) in returns[expr[1]]:
            alternatives.append(name(expr[1], i, j))
          if (i, j) in returns[expr[2]]:
            other_phase = any(a == i and b != j for a, b in returns[expr[1]])
            commitment = "!" + success(expr[1], i) + " " if other_phase else ""
            alternatives.append(commitment + name(expr[2], i, j))
        elif kind in ("!", "&") and i == j:
          alternatives = [kind + success(expr[1], i)]
        elif kind == "star":
          alternatives = [name(expr[1], i, k) + " " + name(expr, k, j)
                          for k in range(width)
                          if (i, k) in returns[expr[1]] and (k, j) in returns[expr]]
          if i == j:
            alternatives.append("!" + success(expr[1], i))
        lines.append(name(expr, i, j) + " = " + (" / ".join(alternatives) or fail) + ";")
  return "\n".join(lines) + "\n"


if __name__ == "__main__":
  import argparse
  from pathlib import Path
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("source", type=Path)
  parser.add_argument("width", type=int)
  parser.add_argument("output", type=Path)
  parser.add_argument("--start", default="S")
  args = parser.parse_args()
  args.output.write_text(inverse_repeat(args.source.read_text(), args.width, args.start))
