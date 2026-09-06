import unittest

from gs_dual_flags import DualFlagVM
from gs_match_heads import StreamingMatcher
from scaffold_circuit import Circuit, Value, PREVIOUS, conjunction as AND
from scaffold_window_workers import flags, matcher
from symbolic_sca2peg import share_expressions
from test_scaffold_circuit_program import scalar
from test_scaffold_window_counter import decode as counter_value
from test_scaffold_flag_packets import decode as flag_values


def fixed_start(kind, quantum, size, lower=0):
  circuit = Circuit("ab")
  count = circuit.get(PREVIOUS, "fixture.count", tuple(range(size + 1)), 0)
  start, mark = count.eq(size - 1), count.eq(lower - 1)
  count = count.map(lambda n: min(n + 1, size))
  worker = (flags if kind == "flags" else matcher)(circuit, quantum=quantum)
  worker.arrive()
  if kind == "flags": worker.mark(mark)
  worker.start(start)
  worker.service()
  answer = worker.output
  if kind == "flags":
    bit, _ = worker.flag_pool.read(worker.flags.root, worker.flags.index)
    answer = AND(worker.mode.eq("done"), bit)
  worker.finalize()
  circuit.put("fixture.count", count)
  return circuit, worker, circuit.machine(answer)


class WindowWorkersTest(unittest.TestCase):
  def check_live(self, circuit, worker, node, observer):
    self.assertEqual(scalar(circuit, node, worker.pc_key), observer.state)
    distances = worker.distances
    for pair in distances.analysis.before[observer.state]:
      key = distances.keys[distances.analysis.colors[pair]]
      self.assertEqual(counter_value(circuit, node, distances.values.bank.counters[key]),
        observer.positions[pair[0]] - observer.positions[pair[1]], pair)

  def test_frozen_oriented_flag_views_during_actual_arrivals(self):
    quantum, size, lower = 3, 4, 2
    with share_expressions():
      circuit, worker, machine = fixed_start("flags", quantum, size, lower)
    for word in ("aaaa", "abba", "abab", "abaa"):
      node, observer = machine.initial_node(), DualFlagVM(word, lower, size, worker.program)
      for index, char in enumerate(word + "ab" * 120):
        node = machine.step(node, char)
        self.assertFalse(scalar(circuit, node, "circuit.fault"), (word, index))
        if index + 1 >= size:
          for _ in range(quantum):
            if not observer.done: observer.step()
          self.check_live(circuit, worker, node, observer)
          self.assertEqual(flag_values(circuit, node, worker.flags), list(reversed(observer.flags)))
        if scalar(circuit, node, worker.mode_key) == "done": break
      self.assertEqual(scalar(circuit, node, worker.mode_key), "done", word)
      self.assertEqual(node.labels[machine.accepting], word[:lower] == word[:lower][::-1])

  def test_matcher_instructions_on_contiguous_actual_input(self):
    quantum, size = 3, 2
    with share_expressions():
      circuit, worker, machine = fixed_start("match", quantum, size)
    for prefix, text in (("ab", "a" * 70), ("ba", "b" * 70)):
      node, observer = machine.initial_node(), StreamingMatcher(prefix[::-1], worker.program)
      for index, char in enumerate(prefix + text):
        node = machine.step(node, char)
        if index + 1 > size: observer.append(char)
        if index + 1 >= size:
          for _ in range(quantum): observer.step()
          self.check_live(circuit, worker, node, observer)
        self.assertFalse(scalar(circuit, node, "circuit.fault"), (prefix, index))


if __name__ == "__main__":
  unittest.main()
