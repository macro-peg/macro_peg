"""Whole Galil controller lowered into finite symbolic scaffold equations.

build_online exposes read/work/output events and derives its internal match
clock from the construction quantum. generate_online_peg supplies the FIFO
and one-node round packing. The legacy build entry retains its old arrival
clock solely for comparison; it is not the raw-input PAL source. No
length-dependent host state or pointer equality is in the emitted machine.
"""
from fpp_finite import LEFT, END
from fpp_subroutine import build_marked_program, SOURCE, MARKS
from dp_finite import build_dp_program, OUTPUT, LOWER
from scaffold_circuit import (Circuit, Value, NEW, PREVIOUS, TRUE, FALSE, choose,
                              conjunction as AND, disjunction as OR, neg as NOT)
from scaffold_circuit_structs import StackPool, Counter
from scaffold_circuit_program import Program
from scaffold_circuit_input import PlaceHead
from scaffold_circuit_search import Search
from scaffold_circuit_chain import Chain
from galil_clock import derive

FIRST = "first:1"
MODES = ("init", "scan", "shift", "copy", "home", "fpp", "mark_end", "choose", "rewind", "replay_start")


def build_online(quantum=64, check_invariants=True, shared_cells=False):
  """Lower the read/work/emit source, with a derived internal match clock.

  a and b are read events; '.' is an input-free work event. This is the
  source interface consumed by the FIFO wrapper, not the final PAL alphabet.
  """
  timing = derive(quantum)
  return build(quantum, timing.match_delay, 2, False, check_invariants,
               online=True, shared_cells=shared_cells)


def build(quantum=64, match_delay=256, budget=2048, coarse=False, check_invariants=True,
          *, online=False, shared_cells=False):
  if type(quantum) is not int or quantum < 1:
    raise ValueError("positive finite instruction quantum required")
  if any(type(n) is not int or n < 2 or n & (n - 1) for n in (match_delay, budget)):
    raise ValueError("clocks must be powers of two of at least two")
  c = Circuit("ab." if online else "ab", check_invariants=check_invariants)
  if online:
    arrival = NOT(c.input().eq("."))
    ready = c.get(PREVIOUS, "online.ready", (False, True), True).eq(True)
    pending = c.get(PREVIOUS, "online.pending", (False, True), False).eq(True)
    c.require(OR(AND(arrival, ready), AND(NOT(arrival), NOT(ready))))
    pending = OR(pending, arrival)
    # Work cells are never appended to input heads; their input field is
    # unobservable by those heads and has a fixed declared binary value.
    c.put("input", Value.select(c.input().eq("b"), Value.constant("b"), Value.constant("a")),
          tuple("ab"), "a")
  else:
    c.put("input", c.input(), tuple("ab"), "a")
    phase = c.get(PREVIOUS, "arrival.phase", tuple(range(budget)), budget - 1)
    arrival = phase.eq(budget - 1)
    c.put("arrival.phase", phase.cycle())

  right_calls = {"R": 2, "L": 2, "C": 1, "W": 0, "V": 2}
  left_calls = {"R": 0, "L": 2, "C": 1, "W": 3, "V": 0}
  heads_pool = {
    "l": StackPool(c, {name + ".l": count for name, count in right_calls.items() if count}),
    "r": StackPool(c, {name + ".r": count for name, count in left_calls.items() if count})}
  units = {name: 3 * (2 + 2 * calls) for name, calls in right_calls.items()}
  rear = StackPool(c, {name + ".in." + side: 1 for name in right_calls for side in ("B", "B2")})
  reverse = StackPool(c, {name + ".in.Fr": count for name, count in units.items()})
  front = StackPool(c, {name + ".in.Br": 2 * count for name, count in units.items()})
  queues_pool = {"F": front, "Br": front, "WF": front,
                 "B": rear, "B2": rear, "WB": rear, "Fr": reverse}
  head_counters = {}
  for role in ("m", "c"):
    head_counters[role] = {}
    for side in ("pos", "neg"):
      counts = {name + ".in." + role + "." + side:
                (units[name] if role == "m" else 2 * units[name]) if side == "pos" else
                (units[name] + right_calls[name] if role == "m" else 1 + right_calls[name])
                for name in right_calls}
      head_counters[role][side] = StackPool(c, counts)
  right, left, center, walker, verifier = heads = [
    PlaceHead(heads_pool, queues_pool, head_counters, name) for name in right_calls]
  for head in heads: head.append(NEW, arrival)

  counts = {"g.len": (6, 2), "g.rad": (3, 1), "g.rem": (1, 2),
            "g.replay": (0, 1), "g.zero": (0, 0), "sp.lo": (0, 0),
            "sp.span": (10, 0), "sp.work": (4, 4), "sp.debt": (3, 1),
            "ch.h": (1, 0), "ch.lag": (1, 1), "ch.dist": (2, 1),
            "ch.bound": (0, 1), "ch.last": (0, 1), "ch.margin": (1, 5), "ch.cycle": (2, 1)}
  pool = StackPool(c, {name + "." + side: count for name, sizes in counts.items()
                       for side, count in zip(("pos", "neg"), sizes)})
  length, radius, remaining, replay, zero = counters = [
    Counter(pool, "g." + name) for name in ("len", "rad", "rem", "replay", "zero")]
  dp = Program(c, build_dp_program("abs"), "dp", quantum=quantum + 8,
               extra_symbols={SOURCE: tuple("abs") + (LEFT, END), LOWER: (LEFT, END, "1")},
               coarse=coarse, shared_cells=shared_cells, extra_moves=8)
  fpp = Program(c, build_marked_program("abs"), "f", quantum=quantum + 8,
                extra_symbols={MARKS: (FIRST,)}, coarse=coarse,
                shared_cells=shared_cells, extra_moves=9)
  search = Search(c, dp, pool, center, walker, radius)
  chain = Chain(c, pool, center, walker, verifier, radius)
  source, marks = fpp.tapes[SOURCE], fpp.tapes[MARKS]

  mode = c.get(PREVIOUS, "g.mode", MODES, "init")
  clock = c.get(PREVIOUS, "g.clock", tuple(range(1, match_delay + 1)), match_delay)
  output = c.get(PREVIOUS, "g.out", (False, True), False).eq(True)
  replaying = c.get(PREVIOUS, "g.replaying", (False, True), False).eq(True)
  odd = c.get(PREVIOUS, "g.odd", (False, True), False).eq(True)
  pair = c.get(PREVIOUS, "g.pair", (0, 1), 0)

  scanning = mode.eq("scan")
  chain_active = NOT(chain.mode.eq("idle"))
  chain.step(dp.tapes[OUTPUT], AND(scanning, chain_active))
  searching = AND(scanning, NOT(chain_active), search.active())
  search.tick(searching, quantum)
  chain.start(AND(searching, search.mode.eq("found")))
  restart = AND(scanning, chain.mode.eq("broken"))
  c.require(AND(NOT(chain.margin.negative()), chain.last.positive(), chain.lag.zero()), restart)
  search.start(chain.last, restart)
  chain.set_mode("idle", restart)
  clock = Value.select(restart, Value.constant(match_delay), clock)

  dispatch = {name: mode.eq(name) for name in MODES}
  init = dispatch["init"]
  right.right(init); left.copy_from(right, init); center.copy_from(right, init)
  length.inc(init); search.start(zero, init)
  mode = Value.select(init, Value.constant("scan"), mode)
  output = choose(init, TRUE, output)

  available = OR(replaying, right.can_right() if online else right.head.can_right())
  ticking = AND(dispatch["scan"], available)
  compare = AND(ticking, clock.eq(1))
  clock = Value.select(ticking, clock.cycle(-1), clock)
  right.right(compare); left.left(compare)
  advance_search = AND(compare, search.active(), chain.mode.eq("idle"))
  search.advance_match(advance_search)
  radius.inc(AND(compare, NOT(advance_search)))
  left_symbol, right_symbol = left.read(), right.read()
  chain.check_pair(left_symbol, compare)
  matched = AND(compare, left_symbol.equal(right_symbol))
  shift = AND(compare, NOT(matched), NOT(replaying), chain.can_shift(),
              chain.prediction().equal(right_symbol))
  fallback = AND(compare, NOT(OR(matched, shift)))
  c.require(NOT(replaying), fallback)
  length.inc(OR(matched, shift)); length.inc(OR(matched, shift))
  chain.matched(OR(AND(matched, NOT(chain.mode.eq("idle"))), shift))
  replayed = AND(matched, replaying)
  replay.dec(replayed)
  replaying = choose(AND(replayed, replay.zero()), FALSE, replaying)
  output = choose(AND(matched, NOT(right.gap)), left.is_first(), output)
  chain.begin_shift(shift); remaining.copy_from(chain.h, shift)
  mode = Value.select(shift, Value.constant("shift"), mode)

  fpp.reset(fallback); walker.copy_from(right, fallback)
  remaining.copy_from(length, fallback); remaining.inc(fallback)
  source.write(Value.constant(LEFT), fallback); source.move(1, fallback)
  search.set_mode("idle", fallback); chain.set_mode("idle", fallback)
  mode = Value.select(fallback, Value.constant("copy"), mode)

  positive = remaining.positive()
  shifting = AND(dispatch["shift"], positive)
  remaining.dec(shifting); center.right(shifting); left.right(shifting); left.right(shifting)
  radius.dec(shifting); length.dec(shifting); length.dec(shifting); chain.shift_one(shifting)
  shifted = AND(dispatch["shift"], NOT(positive))
  mode = Value.select(shifted, Value.constant("scan"), mode)
  output = choose(AND(shifted, NOT(right.gap)), left.is_first(), output)

  positive = remaining.positive()
  copying = AND(dispatch["copy"], positive)
  symbol = walker.read()
  c.require(NOT(symbol.eq(None)), copying)
  source.write(Value({s: guard for s, guard in symbol.cases.items() if s is not None}), copying)
  source.move(1, copying); walker.left(copying); remaining.dec(copying)
  copied = AND(dispatch["copy"], NOT(positive))
  source.write(Value.constant(END), copied)
  mode = Value.select(copied, Value.constant("home"), mode)

  home = source.focus.eq(LEFT)
  returned = AND(dispatch["home"], home)
  fpp.start(returned); mode = Value.select(returned, Value.constant("fpp"), mode)
  source.move(-1, AND(dispatch["home"], NOT(home)))
  c.require(NOT(fpp.done), dispatch["fpp"])
  for _ in range(quantum): fpp.step(dispatch["fpp"])
  finished = AND(dispatch["fpp"], fpp.done)
  marks.move(1, finished); marks.write(Value.constant(FIRST), finished); marks.move(1, finished)
  mode = Value.select(finished, Value.constant("mark_end"), mode)

  end = marks.focus.eq(END)
  marked = AND(dispatch["mark_end"], end)
  marks.move(-1, marked); odd = choose(marked, FALSE, odd)
  mode = Value.select(marked, Value.constant("choose"), mode)
  marks.move(1, AND(dispatch["mark_end"], NOT(end)))

  candidate = AND(odd, OR(marks.focus.eq("1"), marks.focus.eq(FIRST)))
  chosen = AND(dispatch["choose"], candidate)
  left.copy_from(right, chosen); center.copy_from(right, chosen)
  length.reset(chosen); length.inc(chosen); radius.reset(chosen)
  pair = Value.select(chosen, Value.constant(0), pair)
  mode = Value.select(chosen, Value.constant("rewind"), mode)
  skip = AND(dispatch["choose"], NOT(candidate))
  marks.move(-1, skip); odd = choose(skip, NOT(odd), odd)

  first = marks.focus.eq(FIRST)
  rewound = AND(dispatch["rewind"], first)
  fpp.reset(rewound); mode = Value.select(rewound, Value.constant("replay_start"), mode)
  rewind = AND(dispatch["rewind"], NOT(first))
  marks.move(-1, rewind); left.left(rewind); length.inc(rewind)
  pair = Value.select(rewind, pair.cycle(), pair)
  center_step = AND(rewind, pair.eq(0))
  center.left(center_step); radius.inc(center_step)

  restarting = dispatch["replay_start"]
  replay.copy_from(radius, restarting)
  right.copy_from(center, restarting); left.copy_from(center, restarting)
  radius.reset(restarting); length.reset(restarting); length.inc(restarting)
  chain.set_mode("idle", restarting); search.start(zero, restarting)
  replaying = choose(restarting, replay.positive(), replaying)
  clock = Value.select(restarting, Value.constant(match_delay), clock)
  mode = Value.select(restarting, Value.constant("scan"), mode)
  output = choose(AND(restarting, NOT(replaying), NOT(right.gap)), left.is_first(), output)

  caught = AND(mode.eq("scan"), NOT(replaying), NOT(right.gap), NOT(right.head.can_right()))
  event = caught
  if online:
    event = AND(caught, pending)
    pending = AND(pending, NOT(event))
    ready = AND(mode.eq("scan"), NOT(replaying), right.gap, NOT(right.head.can_right()))
    c.require(NOT(AND(ready, pending)))
    for key, value in (("online.ready", ready), ("online.pending", pending),
                       ("online.event", event)):
      c.put(key, Value.select(value, Value.constant(True), Value.constant(False)),
            (False, True), key == "online.ready")
  for head in heads: head.finalize()
  for counter in counters: counter.finalize()
  search.finalize(); chain.finalize(); fpp.finalize()
  c.put("g.mode", mode); c.put("g.clock", clock); c.put("g.pair", pair)
  for name, value in (("g.out", output), ("g.replaying", replaying), ("g.odd", odd)):
    c.put(name, Value.select(value, Value.constant(True), Value.constant(False)))
  c.views = {"heads": heads, "counters": counters, "search": search, "chain": chain, "fpp": fpp}
  machine = c.machine(AND(event, output), initial_accepting=True)
  return c, machine
