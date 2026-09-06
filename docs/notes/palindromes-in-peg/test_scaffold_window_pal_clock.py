"""Test the actual PAL stage wiring with artificial deadline-filling workers.

These workers supply true flags one per arrival and always report an outer
match. This isolates birth, retirement, capture, and consumption; it is not
a test or implementation of the GS algorithm or complete PAL recognition.
"""
import unittest
from unittest.mock import patch

from phase_peg import Grammar
from scaffold_circuit import Circuit, Value, PREVIOUS, TRUE, conjunction as AND, neg
from scaffold_circuit_structs import Stack
from scaffold_flag_packets import FlagPackets, FlagStack, PacketWriter
from scaffold_window_pal import WindowPAL
from test_scaffold_circuit_program import scalar


class AlwaysMatching:
  def __init__(self, circuit, prefix, quantum):
    self.output = TRUE

  def arrive(self): pass
  def start(self, enabled): pass
  def service(self): pass
  def finalize(self): pass


class DeadlineFlags:
  def __init__(self, circuit, prefix, quantum):
    self.circuit, self.prefix = circuit, prefix
    self.flag_pool = FlagPackets(circuit, 1, prefix + ".packets")
    self.flags = FlagStack(self.flag_pool, prefix + ".flags")
    self.mode_key = prefix + ".mode"
    self.mode = circuit.get(PREVIOUS, self.mode_key, ("idle", "run", "done"), "idle")

  def attach(self, half):
    self.half = half
    self.remaining = Stack(half.pool, self.prefix + ".remaining", "cells")

  def arrive(self): pass
  def mark(self, enabled): pass

  def reset_flags(self, enabled):
    self.flags.clear(enabled)
    self.mode = Value.select(enabled, Value.constant("idle"), self.mode)

  def start(self, enabled):
    self.circuit.require(neg(self.mode.eq("run")), enabled)
    self.remaining.copy_from(self.half, enabled)
    self.flags.clear(enabled)
    self.mode = Value.select(enabled, Value.constant("run"), self.mode)

  def service(self):
    active = self.mode.eq("run")
    self.remaining.drop(active)
    PacketWriter(self.flags).push(TRUE, active)
    self.mode = Value.select(AND(active, self.remaining.empty()), Value.constant("done"), self.mode)

  def finalize(self):
    self.remaining.finalize()
    self.flags.finalize()
    self.circuit.put(self.mode_key, self.mode)


class WindowPALClockTest(unittest.TestCase):
  def test_release_capture_and_consumption_through_reused_stages(self):
    circuit = Circuit("ab")
    with patch("scaffold_window_pal.matcher", AlwaysMatching), patch("scaffold_window_pal.flags", DeadlineFlags):
      controller = WindowPAL(circuit)
    for stage, worker in zip(controller.stages, controller.flags):
      worker.attach(stage.half)
    controller.tick()
    controller.finalize()
    machine = circuit.machine(controller.output, initial_accepting=True)
    node, births, slot, word = machine.initial_node(), {}, 0, ""
    for now in range(1, 131):
      char = "abbaba"[(now - 1) % 6]
      word += char
      node = machine.step(node, char)
      if now >= 2 and now & (now - 1) == 0:
        births[slot], slot = now, 1 - slot
      self.assertFalse(scalar(circuit, node, "circuit.fault"), now)
      self.assertEqual(node.labels[machine.accepting], now == 1 or now >= 4 or word[0] == word[-1], now)
      for index, stage in enumerate(controller.stages):
        self.assertEqual(scalar(circuit, node, stage.alive_key), index in births, now)
        if index in births:
          width = births[index]
          self.assertEqual(scalar(circuit, node, stage.interval_key), (now - width) // (width // 2), now)
    grammar = Grammar(machine.compile())
    for word in ("", "a", "ab", "aba", "abb", "aaaa", "abbaab", "a" * 17, "ab" * 17):
      self.assertEqual(grammar.accepts(word), len(word) not in (2, 3) or word[0] == word[-1], word)


if __name__ == "__main__":
  unittest.main()
