"""Finite local FIFO wrapper for an online read/work/output scaffold.

Wrapper events a/b enqueue one real input; '.' performs one source service
unit. The source sees a/b only at its read boundary, otherwise '.'. The
buffer uses the existing persistent real-time queue, with a fixed number of
stack operations per wrapper transition. A later round packing binds one
enqueue and the derived number of service transitions to each real symbol.
"""
from symbolic_sca2peg import (Scaffold, Expr, TRUE, FALSE, SELF, NULL, symbol,
                              old, pointer, read, edge, exists, present, select)
from scaffold_circuit import (Circuit, Value, NEW, PREVIOUS, choose,
                              conjunction as AND, disjunction as OR, neg as NOT)
from scaffold_circuit_structs import StackPool, Queue
from scaffold_round import pack_round


PREFIX = "source."


def substitute_source(source, symbols):
  """Rename source fields and substitute its input event predicates."""
  memo = {}

  def rewrite(expression):
    work = [(expression, False)]
    while work:
      expr, done = work.pop()
      if id(expr) in memo: continue
      op, *args = expr
      children = args if op in ("not", "and", "or", "select", "present") else \
        args[:1] if op in ("read", "edge") else ()
      if not done:
        work.append((expr, True))
        work.extend((child, False) for child in children)
        continue
      if op == "symbol": value = symbols[args[0]]
      elif op == "old": value = old(tuple(PREFIX + key for key in args[0]), PREFIX + args[1])
      elif op == "exists": value = exists(tuple(PREFIX + key for key in args[0]))
      elif op == "pointer": value = pointer(tuple(PREFIX + key for key in args[0]))
      elif op == "read": value = read(memo[id(args[0])], PREFIX + args[1])
      elif op == "edge": value = edge(memo[id(args[0])], PREFIX + args[1])
      elif children: value = Expr((op, *(memo[id(child)] for child in children)))
      else: value = expr
      memo[id(expr)] = value
    return memo[id(expression)]

  return ({key: rewrite(expr) for key, expr in source.labels.items()},
          {key: rewrite(expr) for key, expr in source.pointers.items()})


def buffer_source(source, ready_label, event_label, value_label):
  if tuple(source.alphabet) != tuple("ab."):
    raise ValueError("source must expose binary read events and '.' work")
  if not source.initial[ready_label]:
    raise ValueError("source must initially await input")
  c = Circuit("ab.")
  ingest = NOT(c.input().eq("."))
  c.put("buffer.symbol", Value.select(c.input().eq("b"), Value.constant("b"), Value.constant("a")),
        tuple("ab"), "a")
  cells = StackPool(c, {"q.F": 0, "q.B": 1, "q.B2": 1,
                        "q.Fr": 6, "q.Br": 12, "q.WF": 0, "q.WB": 0})
  counters = StackPool(c, {"q.m.pos": 6, "q.m.neg": 7,
                           "q.c.pos": 12, "q.c.neg": 2})
  queue = Queue(cells, counters, "q")
  queue.push(NEW, ingest)
  queue.work()
  ready = old((), PREFIX + ready_label)
  advance = AND(NOT(ingest), OR(NOT(ready), NOT(queue.empty())))
  take = AND(advance, ready)
  offered = queue.pop(take)
  char = c.get(offered, "buffer.symbol", tuple("ab"), "a")
  queue.work()
  labels, pointers = substitute_source(source, {
    "a": AND(take, char.eq("a")), "b": AND(take, char.eq("b")), ".": NOT(take)})
  complete = AND(advance, labels[event_label], queue.empty())
  previous_answer = c.get(PREVIOUS, "buffer.answer", (False, True), True).eq(True)
  answer = choose(ingest, FALSE, choose(complete, labels[value_label], previous_answer))
  c.put("buffer.answer", Value.select(answer, Value.constant(True), Value.constant(False)))
  queue.finalize()
  # Declare the renamed source fields before validating the combined graph;
  # the queue dispatch and answer expressions already refer to those fields.
  for key, value in source.initial.items():
    c.initial[PREFIX + key] = value
    c.labels[PREFIX + key] = choose(advance, labels[key], old((), PREFIX + key))
  for key, expr in pointers.items():
    c.pointers[PREFIX + key] = select(advance, expr, pointer((PREFIX + key,)))
  machine = c.machine(answer, initial_accepting=True)
  return c, machine


def pack_service(wrapper, service, lazy=False):
  if type(service) is not int or service < 1:
    raise ValueError("positive finite source service required")
  if tuple(wrapper.alphabet) != tuple("ab."):
    raise ValueError("expected a buffered source event alphabet")
  inputs = [{"a": symbol("a"), "b": symbol("b"), ".": FALSE}]
  inputs += [{"a": FALSE, "b": FALSE, ".": TRUE}] * service
  if lazy:
    from scaffold_round_lazy import pack_round_lazy
    return pack_round_lazy([wrapper] * (service + 1), inputs, "ab")
  return pack_round([wrapper] * (service + 1), inputs, "ab")
