"""Constant-field propagation and query closure for finite scaffold equations.

A label is folded only when its sentinel value and every transition agree.
A null pointer field is null at the sentinel and on every transition. These
facts are closed to a fixed point; input samples do not participate.
"""
from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, NULL, Expr, pointer,
                              old, read, edge, present)
from scaffold_circuit import (neg, conjunction as AND, disjunction as OR,
                              choose_pointer)


def children(expression):
  op = expression[0]
  if op in ("read", "edge"): return expression[1:2]
  if op in ("not", "present", "and", "or", "select"): return expression[1:]
  return ()


def project(initial, labels, pointers, roots, accepting, alphabet):
  live_labels, live_pointers, seen = set(), set(), set()
  work = [("label", key) for key in roots]
  while work:
    kind, item = work.pop()
    if kind == "label":
      if item in live_labels: continue
      live_labels.add(item)
      work.append(("expr", labels[item]))
    elif kind == "pointer":
      if item in live_pointers: continue
      live_pointers.add(item)
      work.append(("expr", pointers[item]))
    else:
      if id(item) in seen: continue
      seen.add(id(item))
      op, *args = item
      if op in ("old", "exists", "pointer"):
        work.extend(("pointer", key) for key in args[0])
      if op == "old": work.append(("label", args[1]))
      if op == "read": work.append(("label", args[1]))
      if op == "edge": work.append(("pointer", args[1]))
      work.extend(("expr", child) for child in children(item))
  # Validation below traverses the live DAG again. Its own visited sets do
  # not need to coexist with this complete reachability scratch set.
  seen.clear()
  return Scaffold({key: value for key, value in initial.items() if key in live_labels},
                   {key: value for key, value in labels.items() if key in live_labels},
                   {key: value for key, value in pointers.items() if key in live_pointers},
                   accepting, alphabet)


def optimize(machine, roots=()):
  labels, pointers = machine.labels, machine.pointers
  constants, nulls = {}, set()
  rounds = 0
  while True:
    new_constants = {key: expression for key, expression in labels.items()
                     if expression == (TRUE if machine.initial[key] else FALSE)}
    new_nulls = {key for key, expression in pointers.items() if expression == NULL}
    if rounds and new_constants == constants and new_nulls == nulls: break
    constants, nulls = new_constants, new_nulls
    memo = {}

    def has(target):
      if target == NULL: return FALSE
      if target in (SELF, pointer(())): return TRUE
      return present(target)

    def path(fields):
      return NULL if any(key in nulls for key in fields) else pointer(fields)

    def get(target, key):
      if target == NULL or constants.get(key) == FALSE: return FALSE
      if constants.get(key) == TRUE: return has(target)
      return old(target[1], key) if target[0] == "pointer" else read(target, key)

    def rewrite(expression):
      work = [(expression, False)]
      while work:
        current, done = work.pop()
        if id(current) in memo: continue
        if not done:
          work.append((current, True))
          work.extend((child, False) for child in children(current))
          continue
        op, *args = current
        if op == "old": result = get(path(args[0]), args[1])
        elif op == "exists": result = has(path(args[0]))
        elif op == "pointer": result = path(args[0])
        elif op == "read": result = get(memo[id(args[0])], args[1])
        elif op == "edge":
          target, field = memo[id(args[0])], args[1]
          result = NULL if target == NULL or field in nulls else edge(target, field)
        elif op == "present": result = has(memo[id(args[0])])
        elif op == "not": result = neg(memo[id(args[0])])
        elif op == "and": result = AND(*(memo[id(child)] for child in args))
        elif op == "or": result = OR(*(memo[id(child)] for child in args))
        elif op == "select": result = choose_pointer(*(memo[id(child)] for child in args))
        else: result = current
        memo[id(current)] = current if result == current else result
      return memo[id(expression)]

    labels = {key: rewrite(expression) for key, expression in labels.items()}
    pointers = {key: rewrite(expression) for key, expression in pointers.items()}
    rounds += 1
  # Rewritten roots own every expression that remains live. Retaining the
  # old-id -> new-expression table through projection and validation can
  # otherwise multiply peak memory for a large, fixed transition circuit.
  memo.clear()
  reduced = project(machine.initial, labels, pointers, (machine.accepting, *roots),
                     machine.accepting, machine.alphabet)
  return reduced, dict(rounds=rounds, constant_labels=len(constants), null_pointers=len(nulls),
                        labels_removed=len(labels) - len(reduced.labels),
                        pointers_removed=len(pointers) - len(reduced.pointers))
