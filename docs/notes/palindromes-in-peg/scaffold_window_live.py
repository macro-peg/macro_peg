"""Live GS head differences as windowed signed registers and a finite ROM."""
from gs_head_liveness import analyze
from scaffold_circuit import TRUE, FALSE, conjunction as AND, choose, neg
from scaffold_window_registers import WindowRegisters


class WindowLiveDistances:
  def __init__(self, circuit, program, names, radius, prefix="distance", *, extra=(),
               availability_distance=True):
    self.program = program
    self.availability_distance = availability_distance
    self.analysis = analyze(program, names, observe_positions=False,
                            availability_distance=availability_distance)
    self.keys = tuple(f"{prefix}.r{i}" for i in range(self.analysis.registers))
    self.length_key = prefix + ".length"
    self.values = WindowRegisters(circuit, (*self.keys, self.length_key, *extra), radius)

  def augment_rows(self, rows, domains):
    """Add simultaneous register-transfer columns to the controller ROM."""
    a = self.analysis
    for state, ((event, _), row) in enumerate(zip(self.program.code, rows)):
      op, *args = event
      for destination, key in enumerate(self.keys):
        row[key + ".source"], row[key + ".reverse"], row[key + ".delta"] = key, False, 0
      row["distance.test"], row["distance.reverse"] = self.keys[0], False
      if op in ("less", "equal", "assert_equal") or op == "available" and self.availability_distance:
        pair, sign = a.canonical(args[0], "OriginalEnd" if op == "available" else args[1])
        row["distance.test"] = None if pair is None else self.keys[a.colors[pair]]
        row["distance.reverse"] = sign == -1
      if op == "move":
        deltas = dict(args[0])
        for pair in a.after[state]:
          row[self.keys[a.colors[pair]] + ".delta"] = deltas.get(pair[0], 0) - deltas.get(pair[1], 0)
      elif op == "copy":
        target, source = args
        for pair in a.after[state]:
          if target not in pair: continue
          original, sign = a.canonical(*(source if name == target else name for name in pair))
          key = self.keys[a.colors[pair]]
          row[key + ".source"] = None if original is None else self.keys[a.colors[original]]
          row[key + ".reverse"] = sign == -1
    for key in (*self.keys, "distance"):
      suffixes = ("source", "reverse", "delta") if key != "distance" else ("test", "reverse")
      for suffix in suffixes:
        field = key + "." + suffix
        domains[field] = tuple(dict.fromkeys(row[field] for row in rows))

  def initialize(self, enabled):
    for pair in self.analysis.before[self.program.start]:
      target = self.keys[self.analysis.colors[pair]]
      if "OriginalEnd" in pair:
        source = self.values.registers[self.length_key]
        self.values.assign(target, source, enabled, TRUE if pair[1] == "OriginalEnd" else FALSE)
      else:
        self.values.reset(target, enabled)

  def initialize_values(self, mapping, enabled):
    if set(mapping) != self.analysis.before[self.program.start]:
      raise ValueError("every live entry distance requires an explicit source")
    for pair, value in mapping.items():
      target = self.keys[self.analysis.colors[pair]]
      if value is None:
        self.values.reset(target, enabled)
      else:
        key, sign = value
        self.values.assign(target, self.values.registers[key], enabled,
                           TRUE if sign == -1 else FALSE)

  def load_one(self, enabled):
    self.values.add(self.length_key, 1, enabled)

  def compare(self, fields):
    which = fields["distance.test"]
    register = self.values.select(which)
    zero, less = self.values.compare_zero(register)
    # An absent source is the constant zero (self comparison).
    return zero, choose(fields["distance.reverse"].eq(True), AND(neg(zero), neg(less)), less)

  def execute(self, fields, enabled):
    snapshots = dict(self.values.registers)
    for key in self.keys:
      which = fields[key + ".source"]
      source = self.values.select(which, snapshots)
      self.values.assign(key, source, enabled, fields[key + ".reverse"].eq(True))
      self.values.add_selected(key, fields[key + ".delta"], enabled)

  def finalize(self):
    self.values.finalize()
