"""Finite straight-line blocks between tape reads, with stack-slot ranks.

A block executes its entry instruction and then all following writes/moves,
ending immediately before the next read or halt. Removing incoming edges to
read/halt states must leave a DAG; this is checked, not assumed. Slot ranks
are longest-path counts for each tape's left/right stack. Instructions with
the same rank cannot allocate that stack twice on one path.
"""
from collections import deque


def analyze(program):
  code = program.code
  cuts = {i for i, row in enumerate(code) if row[0] in ("read", "halt")}
  successors = []
  for row in code:
    if row[0] == "read": targets = tuple(dict.fromkeys(row[2].values()))
    elif row[0] in ("write", "move"): targets = (row[3],)
    elif row[0] == "halt": targets = ()
    else: raise ValueError("read blocks require read/write/move/halt instructions")
    successors.append(tuple(target for target in targets if target not in cuts))
  incoming = [0] * len(code)
  for targets in successors:
    for target in targets: incoming[target] += 1
  queue = deque(i for i, degree in enumerate(incoming) if degree == 0)
  order = []
  while queue:
    node = queue.popleft(); order.append(node)
    for target in successors[node]:
      incoming[target] -= 1
      if incoming[target] == 0: queue.append(target)
  if len(order) != len(code):
    raise ValueError("non-reading instruction cycle has no finite block bound")
  ranks = [[0] * (2 * program.ntapes) for row in code]
  slots, bounds = {}, [0] * (2 * program.ntapes)
  lengths, longest = [0] * len(code), 0
  for node in order:
    row = code[node]
    lengths[node] += 1; longest = max(longest, lengths[node])
    if row[0] == "move":
      side = 2 * row[1] + (row[2] == -1)
      ranks[node][side] += 1
      slots[node] = ranks[node][side] - 1
    for side, rank in enumerate(ranks[node]): bounds[side] = max(bounds[side], rank)
    for target in successors[node]:
      lengths[target] = max(lengths[target], lengths[node])
      ranks[target] = [max(a, b) for a, b in zip(ranks[target], ranks[node])]
  return {"order": tuple(order), "cuts": frozenset(cuts), "slots": slots,
          "bounds": tuple(bounds), "max_instructions": longest}


def step(execution):
  """Independent concrete block semantics on the finite tape interpreter."""
  execution.step()
  while not execution.done and execution.program.code[execution.state][0] not in ("read", "halt"):
    execution.step()
