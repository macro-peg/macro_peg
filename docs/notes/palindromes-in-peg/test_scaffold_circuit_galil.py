"""State-level comparison of the entire lowered controller against its source.

Fixtures re-encode immutable stacks by contents, preserving input symbols,
queue rotation state, every tape, and all unary counters. The generated machine
has no identity tests, so allocation-slot names and node sharing are immaterial.
"""
from collections import ChainMap
import unittest
from unittest.mock import patch

import scaffold_galil as reference
import scaffold_search
from scaffold_program import ProgramView
from scaffold_circuit_galil import build, build_online
from symbolic_sca2peg import Node
from scavm import VM
from fpp_subroutine import build_marked_program
from dp_finite import build_dp_program
from test_scaffold_circuit_program import scalar


def native_cells(root, name):
  node = root.ptr[name + ".top"]
  creator, slot = root.label[name + ".tname"], root.label[name + ".tslot"]
  result = []
  while node is not None:
    prefix = f"{creator}.{slot}."
    result.append((node.ptr[prefix + "val"], node.label[prefix + "vs"]))
    below = node.ptr[prefix + "below"]
    creator, slot = node.label[prefix + "bname"], node.label[prefix + "bslot"]
    node = below
  return result


def views(circuit):
  stacks, fields, focuses = [], [], []
  v = circuit.views
  counters = list(v["counters"])
  search, chain = v["search"], v["chain"]
  counters += [search.lower, search.span, search.work, search.debt,
               chain.h, chain.lag, chain.distance, chain.boundary, chain.last, chain.margin, chain.cycle]
  for head in v["heads"]:
    inner, queue = head.head, head.head.incoming
    fields.extend(((head.gap_key, head.gap_key, bool), (queue.phase_key, queue.phase_key, str)))
    focuses.append(inner.focus_key)
    stacks.extend((s, "input") for s in (inner.left_stack, inner.right_stack, *queue.s.values()))
    counters.extend((queue.m, queue.c))
  for counter in counters:
    stacks.extend((s, "counter") for s in (counter.pos, counter.neg))
  for program in (v["fpp"], search.program):
    fields.extend(((program.pc_key, program.pc_key, int),
                   (program.done_key, program.name + ".halted", bool)))
  for tape in (*v["fpp"].tapes, *search.program.tapes, chain.period):
    fields.append((tape.symbol_key, tape.symbol_key, str))
    stacks.extend((s, tape.name) for s in (tape.left, tape.right))
  fields.extend((key, key, convert) for key, convert in (
    ("g.mode", str), ("g.clock", int), ("g.out", bool), ("g.replaying", bool), ("g.odd", bool), ("g.pair", int),
    ("sp.mode", str), ("sp.final", bool), ("sp.quarter", int),
    ("ch.mode", str), ("ch.dir", int), ("ch.phase", int), ("ch.only", bool)))
  return stacks, fields, focuses


def native_values(root, stack, kind):
  result = []
  for value, slot in native_cells(root, stack.name):
    if kind == "input": result.append(None if value is None else value.label["input"])
    elif kind == "counter": result.append(None)
    else: result.append(value.label[f"{kind}.v{slot}"])
  return result


def circuit_values(circuit, root, stack, kind):
  node, tag = root.pointers[stack.root_key], scalar(circuit, root, stack.tag_key)
  result = []
  while node is not None:
    if kind == "input":
      value = node.pointers[stack.pool.key(tag, "value")]
      result.append(None if value is None else scalar(circuit, value, "input"))
    else:
      result.append(scalar(circuit, node, stack.pool.key(tag, "data")))
    node, tag = (node.pointers[stack.pool.key(tag, "below")],
                 scalar(circuit, node, stack.pool.key(tag, "tag")))
  return result


def encode(circuit, machine, root, new_input, budget, online_ready=None, pending=False):
  if root is None: return machine.initial_node()
  nulls, defaults = dict.fromkeys(machine.pointers), machine.initial

  def put(labels, key, value):
    domain = circuit.domains[key][0]
    index = domain.index(value)
    for bit in range(max(0, (len(domain) - 1).bit_length())):
      labels[circuit._label(key, bit)] = bool(index & (1 << bit))

  def input_node(symbol):
    if symbol is None: return None
    labels = {}; put(labels, "input", symbol)
    return Node(ChainMap(labels, defaults), nulls)

  labels, pointers = {}, {}
  stacks, fields, focuses = views(circuit)
  for key, original, convert in fields: put(labels, key, convert(root.label[original]))
  put(labels, "input", root.label["input"] or "a")
  if online_ready is None:
    put(labels, "arrival.phase", budget - 1 if new_input else 0)
  else:
    put(labels, "online.ready", online_ready)
    put(labels, "online.pending", pending)
  for key in focuses:
    value = root.ptr[key]
    pointers[key] = input_node(None if value is None else value.label["input"])
  for stack, kind in stacks:
    tag, below = stack.pool.tags[0], None
    for value in reversed(native_values(root, stack, kind)):
      cell_labels, cell_pointers = {}, {}
      put(cell_labels, stack.pool.key(tag, "tag"), tag)
      put(cell_labels, stack.pool.key(tag, "data"), None if kind in ("input", "counter") else value)
      cell_pointers[stack.pool.key(tag, "below")] = below
      cell_pointers[stack.pool.key(tag, "value")] = input_node(value) if kind == "input" else None
      below = Node(ChainMap(cell_labels, defaults), ChainMap(cell_pointers, nulls))
    pointers[stack.root_key] = below
    put(labels, stack.tag_key, tag)
  return Node(ChainMap(labels, defaults), ChainMap(pointers, nulls))


class CircuitGalilTest(unittest.TestCase):
  def test_online_read_work_output_and_state_match_source(self):
    self.compare_online(False)

  def test_online_state_with_shared_instruction_cells_matches_source(self):
    self.compare_online(True)

  def compare_online(self, shared_cells):
    circuit, machine = build_online(quantum=1, shared_cells=shared_cells)
    samples, seen = [(None, "a", True, False)], set()
    for word in ("ab", "abba", "a" * 12, "ab" * 10, "a" * 8 + "b" + "a" * 8):
      source = reference.OnlineGalil()
      for char in word:
        while True:
          previous, ready, pending = source.vm.top, source.input_ready, source._output_pending
          event = char if ready else "."
          if previous is not None:
            lab = previous.label
            key = (lab["g.mode"], lab["sp.mode"], lab["ch.mode"], lab["ch.only"],
                   lab["g.replaying"], lab["g.odd"], lab["g.pair"], lab["g.clock"] == "1",
                   ready, pending)
            if key not in seen:
              seen.add(key)
              samples.append((previous, event, ready, pending))
          result = source.read(char) if ready else source.work()
          if result.input_ready: break
    stacks, fields, focuses = views(circuit)
    for index, (previous, event, ready, pending) in enumerate(samples):
      root = encode(circuit, machine, previous, event != ".", 2, ready, pending)
      source = reference.OnlineGalil(quantum=1)
      source.vm.top, source.vm.t = previous, -1 if previous is None else previous.t
      source.input_ready, source._output_pending = ready, pending
      expected = source.read(event) if event != "." else source.work()
      actual = machine.step(root, event)
      self.assertFalse(scalar(circuit, actual, "circuit.fault"), index)
      self.assertEqual(scalar(circuit, actual, "online.event"), expected.output is not None, index)
      self.assertEqual(scalar(circuit, actual, "online.ready"), expected.input_ready, index)
      self.assertEqual(scalar(circuit, actual, "online.pending"), source._output_pending, index)
      self.assertEqual(actual.labels[machine.accepting], expected.output == 1, index)
      for key, original, convert in fields:
        self.assertEqual(scalar(circuit, actual, key), convert(source.vm.top.label[original]), (index, key))
      for key in focuses:
        value, original = actual.pointers[key], source.vm.top.ptr[key]
        self.assertEqual(None if value is None else scalar(circuit, value, "input"),
                         None if original is None else original.label["input"], (index, key))
      for stack, kind in stacks:
        self.assertEqual(circuit_values(circuit, actual, stack, kind),
                         native_values(source.vm.top, stack, kind), (index, stack.name))

  def test_whole_transition_matches_native_modes_tapes_heads_and_counters(self):
    # One instruction is sufficient here because the generic finite-program
    # lowering separately checks multiple instructions on one physical node.
    self.compare(False)

  def test_read_block_transition_matches_native_modes_tapes_heads_and_counters(self):
    self.compare(True)

  def compare(self, coarse):
    circuit, machine = build(quantum=1, coarse=coarse)
    kernels = build_marked_program("abs"), build_dp_program("abs")
    samples, seen = [(None, "a", True)], set()
    for word in ("ab", "abba", "a" * 16, "ab" * 12, "ab" + "a" * 20 + "ba"):
      vm = VM()
      for char in word:
        new_input, caught = True, False
        while not caught:
          previous = vm.top
          if previous is not None:
            lab = previous.label
            key = (lab["g.mode"], lab["sp.mode"], lab["ch.mode"], lab["ch.only"],
                   lab["g.replaying"], lab["g.odd"], lab["g.pair"], lab["g.clock"] == "1")
            if key not in seen:
              seen.add(key); samples.append((previous, char, new_input))
          _, caught, _ = reference.step(vm, char, new_input, kernels)
          new_input = False
    stacks, fields, focuses = views(circuit)
    self.assertEqual({sample[0].label["g.mode"] for sample in samples[1:]}, set(reference_mode for reference_mode in
      ("scan", "shift", "copy", "home", "fpp", "mark_end", "choose", "rewind", "replay_start")))
    class ReadProgramView(ProgramView):
      def step(self):
        self.step_read_block()
    program_view = ReadProgramView if coarse else ProgramView
    from dataclasses import replace
    # This fixture compares one old experimental tick; it intentionally
    # retains that fixture's match clock instead of deriving a new schedule.
    timing = replace(reference.DEFAULT_TIMING, quantum=1)
    with patch.object(reference, "DEFAULT_TIMING", timing), \
         patch.object(reference, "ProgramView", program_view), \
         patch.object(scaffold_search, "ProgramView", program_view):
      for index, (previous, char, arrival) in enumerate(samples):
        root = encode(circuit, machine, previous, arrival, 2048)
        vm = VM()
        vm.top, vm.t = previous, -1 if previous is None else previous.t
        report, _, _ = reference.step(vm, char, arrival, kernels)
        result = machine.step(root, char)
        self.assertFalse(scalar(circuit, result, "circuit.fault"), (index, previous.label["g.mode"] if previous else "init"))
        self.assertEqual(result.labels[machine.accepting], bool(report), index)
        for key, original, convert in fields:
          self.assertEqual(scalar(circuit, result, key), convert(vm.top.label[original]), (index, key))
        for key in focuses:
          actual, expected = result.pointers[key], vm.top.ptr[key]
          self.assertEqual(None if actual is None else scalar(circuit, actual, "input"),
                           None if expected is None else expected.label["input"], (index, key))
        for stack, kind in stacks:
          self.assertEqual(circuit_values(circuit, result, stack, kind), native_values(vm.top, stack, kind),
                           (index, stack.name))


if __name__ == "__main__":
  unittest.main()
