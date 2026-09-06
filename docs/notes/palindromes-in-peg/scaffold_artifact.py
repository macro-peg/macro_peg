"""Flat, data-only checkpoints for large finite scaffold expression DAGs.

Nodes are written in topological order with integer child references. Loading
does not recurse through the expression graph or execute construction code.
These files are compiler intermediates, not ordinary PEG deliverables.
"""
import gzip
import marshal
from pathlib import Path

from symbolic_sca2peg import Expr, Scaffold


MAGIC = b"finite-scaffold-dag-1\n"
OPS = ("const", "symbol", "self", "null", "old", "exists", "pointer",
       "read", "edge", "not", "present", "and", "or", "select")


def children(expression):
  op = expression[0]
  if op in ("read", "edge"): return expression[1:2]
  if op in ("not", "present", "and", "or", "select"): return expression[1:]
  return ()


def write(machine, path, metadata=None):
  path = Path(path)
  temporary = path.with_suffix(path.suffix + ".partial")
  open_stream = gzip.open if path.suffix == ".gz" else open
  seen = {}
  with open_stream(temporary, "wb") as stream:
    stream.write(MAGIC)
    marshal.dump((machine.initial, machine.accepting, machine.alphabet, metadata), stream)
    for expression in (*machine.labels.values(), *machine.pointers.values()):
      work = [(expression, False)]
      while work:
        current, done = work.pop()
        if id(current) in seen: continue
        if not done:
          work.append((current, True))
          work.extend((child, False) for child in children(current))
          continue
        op, *args = current
        if op in ("read", "edge"): args[0] = seen[id(args[0])]
        elif children(current): args = [seen[id(child)] for child in args]
        marshal.dump((OPS.index(op), *args), stream)
        seen[id(current)] = len(seen)
    marshal.dump(None, stream)
    marshal.dump(({key: seen[id(expr)] for key, expr in machine.labels.items()},
                  {key: seen[id(expr)] for key, expr in machine.pointers.items()}), stream)
  temporary.replace(path)
  return len(seen)


def read(path):
  path = Path(path)
  open_stream = gzip.open if path.suffix == ".gz" else open
  nodes = []
  with open_stream(path, "rb") as stream:
    if stream.read(len(MAGIC)) != MAGIC:
      raise ValueError("not a finite scaffold DAG artifact")
    initial, accepting, alphabet, metadata = marshal.load(stream)

    def child(index):
      if type(index) is not int or not 0 <= index < len(nodes):
        raise ValueError("artifact child must precede its parent")
      return nodes[index]

    while True:
      row = marshal.load(stream)
      if row is None: break
      if not isinstance(row, tuple) or not row or type(row[0]) is not int or not 0 <= row[0] < len(OPS):
        raise ValueError("invalid scaffold artifact node")
      op, args = OPS[row[0]], list(row[1:])
      if op in ("read", "edge"): args[0] = child(args[0])
      elif op in ("not", "present", "and", "or", "select"):
        args = [child(index) for index in args]
      nodes.append(Expr((op, *args)))
    labels, pointers = marshal.load(stream)
    if stream.read(1): raise ValueError("trailing scaffold artifact data")
  return Scaffold(initial, {key: child(index) for key, index in labels.items()},
                   {key: child(index) for key, index in pointers.items()},
                   accepting, alphabet), metadata
