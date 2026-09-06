"""A fixed bank of queues sharing one physical instruction's cell storage.

In each transition exactly one bank operation may allocate: push, pop, or
one rotation work unit on one selected queue. Copies allocate no cells.
Three separately scheduled work units follow each logical push/pop before
another logical operation on that queue. They are not unrolled into a bank
of per-object allocation slots.
"""
from scaffold_circuit import (Circuit, Value, NEW, EMPTY, TRUE, FALSE,
                              disjunction, conjunction, neg)
from scaffold_circuit_structs import StackPool, Queue


class QueueRegisters:
  def __init__(self, circuit, names):
    self.circuit, self.names = circuit, tuple(names)
    if not self.names or len(self.names) != len(set(self.names)):
      raise ValueError("a fixed nonempty set of distinct queue names is required")
    def pool(slots=1):
      return StackPool(circuit, {"cells": slots})
    front, rear, reverse = pool(), pool(), pool()
    self.cells = {"F": front, "WF": front, "Br": front, "B": rear, "WB": rear,
                  "B2": rear, "Fr": reverse}
    self.counter_cells = {"m": {"pos": pool(), "neg": pool()},
                          "c": {"pos": pool(2), "neg": pool()}}
    self.queues = {name: Queue(self.cells, self.counter_cells, name, shared_slots=True)
                   for name in self.names}

  def finalize(self):
    for queue in self.queues.values():
      queue.finalize()


def fixture():
  circuit = Circuit("abAB>.],xy")
  bank = QueueRegisters(circuit, ("q0", "q1"))
  char = circuit.input()
  circuit.put("input", Value.select(disjunction(char.eq("b"), char.eq("B")),
                                    Value.constant("b"), Value.constant("a")), tuple("ab"), "a")
  answer = FALSE
  for index, (push_a, push_b, pop, work) in enumerate((("a", "b", ">", "."),
                                                     ("A", "B", "]", ","))):
    queue = bank.queues[f"q{index}"]
    queue.push(NEW, disjunction(char.eq(push_a), char.eq(push_b)))
    take = conjunction(char.eq(pop), neg(queue.s["F"].empty()))
    value = queue.pop(take)
    answer = disjunction(answer, conjunction(take, circuit.get(value, "input", tuple("ab"), "a").eq("a")))
    queue.work_unit(char.eq(work))
  bank.queues["q1"].copy_from(bank.queues["q0"], char.eq("x"))
  bank.queues["q0"].copy_from(bank.queues["q1"], char.eq("y"))
  bank.finalize()
  return circuit, bank, circuit.machine(answer)
