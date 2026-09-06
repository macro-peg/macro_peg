"""Executable FIFO service specification; local SCA lowering is separate.

The deque models the buffer for this reference. It is not claimed to be a
finite local transition table. Output decisions depend only on source events
and buffer emptiness; diagnostic counts do not guide the source or scheduler.
"""
from collections import deque

from scaffold_galil import OnlineGalil, FPP_QUANTUM


class BufferedSource:
  def __init__(self, source, service):
    if type(service) is not int or service < 1:
      raise ValueError("positive integral source service required")
    self.source, self.service, self.pending = source, service, deque()
    self.offered = self.reads = self.outputs = self.transitions = 0
    self.max_pending = 0
    self.last_completed = False

  def read(self, char):
    if char not in ("a", "b"):
      raise ValueError("one binary input symbol required")
    self.pending.append(char)
    self.offered += 1
    self.max_pending = max(self.max_pending, len(self.pending))
    answer, self.last_completed = 0, False
    for _ in range(self.service):
      if self.source.input_ready:
        if not self.pending:
          break  # remaining service transitions are quiescent
        result = self.source.read(self.pending.popleft())
        self.reads += 1
      else:
        result = self.source.work()
      self.transitions += 1
      if result.output is not None:
        self.outputs += 1
        if not self.pending:
          answer, self.last_completed = result.output, True
    return answer


class RealtimeGalil(BufferedSource):
  def __init__(self, quantum=FPP_QUANTUM):
    source = OnlineGalil(quantum)
    super().__init__(source, source.timing.service)

  @staticmethod
  def accepts_empty():
    return True
