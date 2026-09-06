"""Queue lowering is checked against a deque, including in-flight rotations."""
from collections import deque
import random
import unittest

from scaffold_circuit import Circuit, Value, NEW, conjunction, disjunction, neg
from scaffold_circuit_structs import StackPool, Queue
from phase_peg import Grammar


def queue_fixture():
  c = Circuit("ab<.")
  c.put("input", c.input(), tuple(c.alphabet), "a")
  cells = StackPool(c, {"q.F": 0, "q.B": 1, "q.B2": 1,
                        "q.Fr": 6, "q.Br": 12, "q.WF": 0, "q.WB": 0})
  counters = StackPool(c, {"q.m.pos": 6, "q.m.neg": 7,
                           "q.c.pos": 12, "q.c.neg": 2})
  q = Queue(cells, counters, "q")
  q.push(NEW, disjunction(c.input().eq("a"), c.input().eq("b")))
  q.work()
  take = conjunction(c.input().eq("<"), neg(q.empty()))
  value = q.pop(take)
  q.work()
  answer = c.get(value, "input", tuple(c.alphabet), "a").eq("a")
  q.finalize()
  return c.machine(conjunction(take, answer))


class CircuitQueueTest(unittest.TestCase):
  def test_deque_equivalence_across_rotations_and_empty_pops(self):
    machine = queue_fixture()
    grammar = Grammar(machine.compile())
    for word in ("a<", "b<", "ab<<", "aabb<<<<", "<<", "a..b..<<"):
      queue, answer = deque(), False
      for char in word:
        answer = bool(char == "<" and queue and queue.popleft() == "a")
        if char in "ab": queue.append(char)
      self.assertEqual(machine.run(word), answer, word)
      self.assertEqual(grammar.accepts(word[::-1]), answer, word)
    random.seed(871)
    word = "a" * 30 + "b" * 20 + "<" * 55 + "".join(random.choices("ab<.", k=250))
    queue, root = deque(), machine.initial_node()
    for char in word:
      expected = bool(char == "<" and queue and queue.popleft() == "a")
      if char in "ab": queue.append(char)
      root = machine.step(root, char)
      self.assertEqual(root.labels[machine.accepting], expected)


if __name__ == "__main__":
  unittest.main()
