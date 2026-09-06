"""Finite Boolean/pointer scaffold equations to ordinary PEG.

Every label is a Boolean component. Transition expressions inspect the input
symbol and bounded paths from the previous root; they cannot inspect pointer
identity. Edges may target self, null, or a bounded old path. Conditional
expressions share grammar rules, without enumerating complete neighborhoods.
An explicit initial sentinel has supplied labels and all-null pointer fields.
The generated PEG recognizes the reversal of the scaffold's input language.
This is an output interface, not a lowering of scaffold_galil's Python code.
"""
from dataclasses import dataclass
from contextlib import contextmanager
import json


class Expr:
  """Immutable tuple-like DAG node with cached hash and no instance dict.

  A tuple subclass with an attached hash allocated a dictionary per node.
  Slot storage keeps the same structural equality/hash and tuple indexing,
  while avoiding that cost for the millions of construction expressions.
  """
  __slots__ = ("_parts", "_hash")
  _pool = None

  def __new__(cls, parts):
    result = super().__new__(cls)
    object.__setattr__(result, "_parts", tuple(parts))
    object.__setattr__(result, "_hash", hash(result._parts))
    if cls._pool is not None:
      return cls._pool.setdefault(result, result)
    return result

  def __hash__(self):
    return self._hash

  def __setattr__(self, name, value):
    raise AttributeError("immutable expression")

  def __len__(self):
    return len(self._parts)

  def __iter__(self):
    return iter(self._parts)

  def __getitem__(self, index):
    return self._parts[index]

  def __repr__(self):
    return repr(self._parts)

  def __eq__(self, other):
    if self is other: return True
    if isinstance(other, Expr):
      return self._hash == other._hash and self._parts == other._parts
    if isinstance(other, tuple): return self._parts == other
    return NotImplemented

  def __ne__(self, other):
    equal = self.__eq__(other)
    return NotImplemented if equal is NotImplemented else not equal


@contextmanager
def share_expressions():
  """Hash-cons equal construction expressions; release the index on exit."""
  previous = Expr._pool
  Expr._pool = {} if previous is None else previous
  try:
    yield
  finally:
    Expr._pool = previous


def clear_expression_cache():
  """Release only the optional interning index, retaining all live DAGs.

  Construction roots own their expression nodes directly. Clearing the
  index between large instruction bursts permits unused temporary nodes to
  die; it does not change any expression, source state, or PEG equation.
  """
  if Expr._pool is not None:
    Expr._pool.clear()


TRUE, FALSE = Expr(("const", True)), Expr(("const", False))
SELF, NULL = Expr(("self",)), Expr(("null",))


def symbol(char):
  return Expr(("symbol", char))


def old(path, label):
  return Expr(("old", tuple(path), label))


def exists(path):
  return Expr(("exists", tuple(path)))


def negate(value):
  return Expr(("not", value))


def both(*values):
  return Expr(("and", *values))


def either(*values):
  return Expr(("or", *values))


def pointer(path):
  return Expr(("pointer", tuple(path)))


def select(condition, yes, no):
  return Expr(("select", condition, yes, no))


def read(target, label):
  """Read a label through a computed old pointer; null reads are false."""
  return Expr(("read", target, label))


def edge(target, field):
  """Follow one edge through a computed old pointer; null stays null."""
  return Expr(("edge", target, field))


def present(target):
  return Expr(("present", target))


@dataclass
class Node:
  labels: dict
  pointers: dict


class Scaffold:
  def __init__(self, initial, labels, pointers, accepting, alphabet="ab"):
    self.initial, self.labels, self.pointers = dict(initial), dict(labels), dict(pointers)
    self.accepting, self.alphabet = accepting, tuple(alphabet)
    self._validated = {kind: set() for kind in ("boolean", "pointer", "old")}
    if set(initial) != set(labels) or any(type(x) is not bool for x in initial.values()):
      raise ValueError("initial Boolean values must cover exactly the label fields")
    if accepting not in labels:
      raise ValueError("unknown acceptance label")
    if not self.alphabet or len(set(self.alphabet)) != len(self.alphabet) or any(
        len(c) != 1 or ord(c) > 0xffff or 0xd800 <= ord(c) <= 0xdfff
        for c in self.alphabet):
      raise ValueError("distinct BMP scalar input characters required")
    for expr in labels.values():
      self._validate(expr, False)
    for expr in pointers.values():
      self._validate(expr, True)
    # This is construction scratch, not part of the machine. Keeping it on
    # every intermediate scaffold retained millions of expression/type pairs.
    for seen in self._validated.values(): seen.clear()

  def _path(self, path):
    if not isinstance(path, tuple) or any(field not in self.pointers for field in path):
      raise ValueError("unknown pointer field in path")

  def _validate(self, expr, is_pointer):
    work = [(expr, "pointer" if is_pointer else "boolean")]
    while work:
      current, expected = work.pop()
      key, seen = id(current), self._validated[expected]
      if key in seen: continue
      if not isinstance(current, (tuple, Expr)) or not current:
        raise ValueError("finite tuple expression required")
      op, *args = current
      children = []
      valid = False
      if expected == "old":
        if op == "self":
          raise ValueError("transition queries must inspect old nodes, not the new self")
        children.append((current, "pointer"))
        if op == "select" and len(args) == 3:
          children.extend(((args[1], "old"), (args[2], "old")))
        valid = True
      elif expected == "pointer":
        if op in ("self", "null") and not args: valid = True
        elif op == "pointer" and len(args) == 1:
          self._path(args[0]); valid = True
        elif op == "select" and len(args) == 3:
          children.extend(((args[0], "boolean"), (args[1], "pointer"), (args[2], "pointer")))
          valid = True
        elif op == "edge" and len(args) == 2 and args[1] in self.pointers:
          children.append((args[0], "old")); valid = True
      else:
        if op == "const" and len(args) == 1 and type(args[0]) is bool: valid = True
        elif op == "symbol" and len(args) == 1 and args[0] in self.alphabet: valid = True
        elif op == "old" and len(args) == 2 and args[1] in self.labels:
          self._path(args[0]); valid = True
        elif op == "exists" and len(args) == 1:
          self._path(args[0]); valid = True
        elif op == "read" and len(args) == 2 and args[1] in self.labels:
          children.append((args[0], "old")); valid = True
        elif op == "present" and len(args) == 1:
          children.append((args[0], "pointer")); valid = True
        elif op in ("and", "or") or (op == "not" and len(args) == 1):
          children.extend((arg, "boolean") for arg in args); valid = True
      if not valid:
        raise ValueError("invalid expression or Boolean/pointer type mismatch")
      seen.add(key)
      work.extend(children)

  def run(self, word):
    root = self.evaluate(word)
    return root is not None and root.labels[self.accepting]

  def initial_node(self):
    return Node(self.initial, dict.fromkeys(self.pointers))

  def evaluate(self, word):
    """Return the last scaffold node for independent state-level verification."""
    root = self.initial_node()
    for char in word:
      root = self.step(root, char)
      if root is None: break
    return root

  def step(self, root, char):
    if char not in self.alphabet:
      return None
    created = Node({}, {})

    def walk(path):
      node = root
      for field in path:
        if node is None:
          return None
        node = node.pointers[field]
      return node

    memo = {}

    def evaluate(expr):
      work, out = [("eval", expr)], None
      while work:
        task = work.pop()
        action = task[0]
        if action == "save": memo[task[1]] = out
        elif action == "not": out = not out
        elif action == "read": out = out is not None and out.labels[task[1]]
        elif action == "edge": out = None if out is None else out.pointers[task[1]]
        elif action == "present": out = out is not None
        elif action == "select": work.append(("eval", task[1] if out else task[2]))
        elif action == "logical":
          op, args, index = task[1:]
          if bool(out) == (op == "or"):
            out = op == "or"
          elif index + 1 == len(args):
            out = op == "and"
          else:
            work.append(("logical", op, args, index + 1))
            work.append(("eval", args[index + 1]))
        else:
          current = task[1]
          if id(current) in memo:
            out = memo[id(current)]; continue
          work.append(("save", id(current)))
          op, *args = current
          if op == "const": out = args[0]
          elif op == "symbol": out = char == args[0]
          elif op == "old":
            node = walk(args[0]); out = node is not None and node.labels[args[1]]
          elif op == "exists": out = walk(args[0]) is not None
          elif op == "not":
            work.append(("not",)); work.append(("eval", args[0]))
          elif op in ("and", "or"):
            if not args: out = op == "and"
            else:
              work.append(("logical", op, args, 0)); work.append(("eval", args[0]))
          elif op == "self": out = created
          elif op == "null": out = None
          elif op == "pointer": out = walk(args[0])
          elif op == "select":
            work.append(("select", args[1], args[2])); work.append(("eval", args[0]))
          elif op in ("read", "edge"):
            work.append((op, args[1])); work.append(("eval", args[0]))
          elif op == "present":
            work.append(("present",)); work.append(("eval", args[0]))
          else: raise AssertionError(op)
      return out

    created.labels = {key: evaluate(expr) for key, expr in self.labels.items()}
    created.pointers = {key: evaluate(expr) for key, expr in self.pointers.items()}
    return created

  def compile(self):
    return "\n".join(self.iter_rules()) + "\n"

  def iter_rules(self):
    """Emit the same finite PEG without retaining its whole text in memory."""
    labels = {key: f"B_{i}" for i, key in enumerate(self.labels)}
    pointers = {key: f"P_{i}" for i, key in enumerate(self.pointers)}
    shared, pending = {}, []
    literal = lambda c: '"\\u0008"' if c == "\b" else json.dumps(c, ensure_ascii=False)

    def path(fields):
      return "." + "".join(" " + pointers[key] for key in fields)

    def expression(expr):
      index = shared.get(expr)
      if index is None:
        index = len(pending)
        shared[expr] = index
        pending.append(expr)
      return f"E_{index}"

    def render(name, expr):
      op, *args = expr
      if op == "const": body = '""' if args[0] else '!""'
      elif op == "symbol": body = "&" + literal(args[0])
      elif op == "old": body = "&(" + path(args[0]) + " " + labels[args[1]] + ")"
      elif op == "exists": body = "&(" + path(args[0]) + ")"
      elif op == "not": body = "!" + expression(args[0])
      elif op == "and": body = " ".join(expression(x) for x in args) or '""'
      elif op == "or": body = " / ".join(expression(x) for x in args) or '!""'
      elif op == "self": body = '""'
      elif op == "null": body = '!""'
      elif op == "pointer": body = path(args[0])
      elif op == "select":
        condition, yes, no = map(expression, args)
        # Guard the fallback: a selected null pointer must remain null.
        body = f"{condition} {yes} / !{condition} {no}"
      elif op == "read": body = "&(" + expression(args[0]) + " " + labels[args[1]] + ")"
      elif op == "edge": body = expression(args[0]) + " " + pointers[args[1]]
      elif op == "present": body = "&" + expression(args[0])
      else: raise AssertionError(op)
      return f"{name} = {body};"

    start = f"S = {labels[self.accepting]} (" + " / ".join(map(literal, self.alphabet)) + ")* !.;"
    yield start
    for key, expr in self.labels.items():
      base = "!. / " if self.initial[key] else ""
      yield f"{labels[key]} = {base}&. {expression(expr)};"
    for key, expr in self.pointers.items():
      yield f"{pointers[key]} = &. {expression(expr)};"
    cursor = 0
    while cursor < len(pending):
      yield render(f"E_{cursor}", pending[cursor])
      cursor += 1
