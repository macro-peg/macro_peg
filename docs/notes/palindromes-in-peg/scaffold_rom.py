"""Finite lookup tables as shared Boolean decision diagrams.

The table is construction data. A read instantiates its fixed Boolean DAG
on the current finite address bits; there is no runtime array access or
semantic callback in the emitted scaffold/PEG.
"""
from scaffold_circuit import Value, TRUE, FALSE, choose


class ROM:
  def __init__(self, rows, domains):
    self.rows = tuple(dict(row) for row in rows)
    self.domains = {key: tuple(domain) for key, domain in domains.items()}
    if not self.rows or any(set(row) != set(domains) for row in self.rows):
      raise ValueError("every finite table row must cover the declared columns")
    if any(not domain or len(set(domain)) != len(domain) for domain in self.domains.values()):
      raise ValueError("finite column values must be distinct")
    self.width = max(0, (len(self.rows) - 1).bit_length())
    self.nodes, unique, memo = [], {}, {}

    def build(values, bit):
      if not any(values): return 0
      if all(values): return 1
      key = bit, values
      if key in memo: return memo[key]
      half = len(values) // 2
      no, yes = build(values[:half], bit - 1), build(values[half:], bit - 1)
      if yes == no:
        result = yes
      else:
        node = bit, yes, no
        if node not in unique:
          unique[node] = len(self.nodes) + 2
          self.nodes.append(node)
        result = unique[node]
      memo[key] = result
      return result

    self.outputs = {}
    padded = self.rows + (self.rows[0],) * ((1 << self.width) - len(self.rows))
    for key, domain in self.domains.items():
      indices = {value: i for i, value in enumerate(domain)}
      encoded = [indices[row[key]] for row in padded]
      self.outputs[key] = tuple(build(tuple(bool(value & (1 << bit)) for value in encoded), self.width - 1)
                                for bit in range((len(domain) - 1).bit_length()))

  def read(self, bits):
    bits = tuple(bits)
    if len(bits) != self.width:
      raise ValueError("ROM address width does not match its finite table")
    expressions = [FALSE, TRUE]
    for bit, yes, no in self.nodes:
      expressions.append(choose(bits[bit], expressions[yes], expressions[no]))
    return {key: Value.encoded(self.domains[key], (expressions[index] for index in outputs))
            for key, outputs in self.outputs.items()}


def controller_table(program, names, readers=None, reader_names=(), *, batched=False, augment=None):
  """Decode a GS finite table, optionally including colored data-head roles."""
  tests = {"equal", "less", "symbols", "available"}
  rows = []
  for state, (event, targets) in enumerate(program.code):
    op, *args = event
    row = dict(op=op, left=names[0], right=names[0], direction=1,
               bit=False, no=targets[0] if targets else state,
               yes=targets[1] if op in tests else targets[0] if targets else state)
    if op in ("equal", "less", "symbols", "assert_equal", "copy"):
      row["left"], row["right"] = args
    elif op == "move":
      if not batched and (len(args[0]) != 1 or abs(args[0][0][1]) != 1):
        raise ValueError("unit movement table required")
      if not batched:
        row["left"], row["direction"] = args[0][0]
    elif op in ("available", "match", "border"):
      row["left"] = args[0]
    elif op == "flag":
      row["bit"] = args[0]
    elif op != "halt":
      raise ValueError("unknown finite GS instruction")
    if readers is not None:
      row.update(data_left=None, data_right=None, data_target=None, data_source=None, data_move=None)
      if batched:
        row.update({"data_delta." + name: 0 for name in reader_names})
      color = lambda name: reader_names[readers.colors[name]]
      if op == "symbols":
        row["data_left"], row["data_right"] = map(color, args)
      elif op == "available":
        row["data_left"] = color(args[0])
      elif op == "copy" and args[0] in readers.after[state]:
        row["data_target"], row["data_source"] = map(color, args)
      elif op == "move":
        if batched:
          for head, delta in args[0]:
            if head in readers.after[state]:
              row["data_delta." + color(head)] += delta
        elif row["left"] in readers.after[state]:
          row["data_move"] = color(row["left"])
    rows.append(row)
  domains = dict(op=tuple(dict.fromkeys(row["op"] for row in rows)), left=tuple(names), right=tuple(names),
                 direction=(-1, 1), bit=(False, True), no=tuple(range(len(rows))), yes=tuple(range(len(rows))))
  if readers is not None:
    domains.update({key: (None, *reader_names) for key in
                    ("data_left", "data_right", "data_target", "data_source", "data_move")})
    if batched:
      for name in reader_names:
        key = "data_delta." + name
        domains[key] = tuple(dict.fromkeys(row[key] for row in rows))
  if augment is not None:
    augment(rows, domains)
  return ROM(rows, domains)
