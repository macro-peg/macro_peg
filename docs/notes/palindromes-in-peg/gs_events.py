"""Resumable indexed GS operations, separate from the direct reference.

One yielded operation is a comparison, bookkeeping event, or output bit.
Integer cursor arithmetic is still an indexed-machine operation. These are
not local SCA instructions; the distinction matters to every clock below.
"""
from gs_overlap import Decomposition, PalindromeView, shift_without_period


WORK = ("work",)


def _first(start, size, k, bound=None):
  p, q = 1, 0
  while p < size and (bound is None or p < bound):
    yield WORK
    while p + q < size and q < (k - 1) * p:
      if not (yield ("compare", start + q, start + p + q)):
        break
      q += 1
      yield WORK
    if q == (k - 1) * p:
      return p, p + q
    p += shift_without_period(q, k)
    q = 0
  return None


def _second(start, size, k, first, reach):
  p, q = 1, 0
  while p < size:
    yield WORK
    while p + q < size:
      if not (yield ("compare", start + q, start + p + q)):
        break
      q += 1
      yield WORK
      if p + q > reach and q >= (k - 1) * p:
        return p
    if k * first <= q <= reach:
      p += first
      q -= first
    else:
      p += shift_without_period(q, k)
      q = 0
  return None


def decomposition(size, k=8):
  if type(size) is not int or size < 0 or type(k) is not int or k < 4:
    raise ValueError("nonnegative size and integer k >= 4 required")
  start = 0
  while True:
    found = yield from _first(start, size - start, k)
    if found is None:
      return Decomposition(start, None, 0)
    first, reach = found
    while reach < size - start:
      if not (yield ("compare", start + reach, start + reach - first)):
        break
      reach += 1
      yield WORK
    second = yield from _second(start, size - start, k, first, reach)
    if second is None:
      return Decomposition(start, first, reach)
    while True:
      found = yield from _first(start, size - start, k, second)
      if found is None:
        break
      start += found[0]
      yield WORK


def borders(size, k=8):
  if type(size) is not int or size < 0 or type(k) is not int or k < 4:
    raise ValueError("nonnegative size and integer k >= 4 required")
  limit, position = size, 1
  while limit:
    part = yield from decomposition(limit, k)
    cut, period, reach = part.cut, part.period, part.reach
    yield WORK
    minimum, matched = max(1, 2 * cut), 0
    while position <= limit - minimum:
      yield WORK
      while position + cut + matched < limit:
        if not (yield ("compare", cut + matched, size - limit + position + cut + matched)):
          break
        matched += 1
        yield WORK
      if position + cut + matched == limit:
        checked = 0
        while checked < cut:
          if not (yield ("compare", checked, size - limit + position + checked)):
            break
          checked += 1
          yield WORK
        if checked == cut:
          yield ("border", limit - position)
      if period is not None and k * period <= matched <= reach:
        position += period
        matched -= period
      else:
        position += shift_without_period(matched, k)
        matched = 0
    limit, position = minimum - 1, 0


def palindrome_flags(size, lower, upper, k=8):
  """Emit flags for lengths upper-1 .. lower, including epsilon when asked."""
  if not 0 <= lower <= upper <= size + 1:
    raise ValueError("invalid palindrome flag interval")
  remaining = upper - 1
  source = borders(2 * size + 1, k)
  response = None
  try:
    while remaining >= max(lower, 1):
      try:
        event = source.send(response)
      except StopIteration:
        break
      response = None
      if event[0] != "border":
        response = yield event
        continue
      length = event[1]
      if length > remaining:
        continue
      if length < lower:
        break
      while remaining > length:
        yield ("flag", False)
        remaining -= 1
      yield ("flag", True)
      remaining -= 1
    while remaining >= lower:
      yield ("flag", remaining == 0)
      remaining -= 1
  finally:
    source.close()


class IndexedJob:
  """Service one yielded indexed operation per step, retaining no trace."""
  def __init__(self, word, program):
    self.word, self.program = word, program
    self.response = None
    self.done = False
    self.result = None
    self.steps = 0

  def step(self):
    if self.done:
      raise RuntimeError("job already completed")
    self.steps += 1
    try:
      event = self.program.send(self.response)
    except StopIteration as end:
      self.done, self.result = True, end.value
      return None
    self.response = None
    if event[0] == "compare":
      self.response = self.word[event[1]] == self.word[event[2]]
    elif event[0] not in ("work", "flag", "border"):
      raise ValueError("unknown indexed operation")
    return event


class PatternMatcher:
  """Incremental GS fixed-pattern matcher with interleaved prefix checking.

  Text is a growing indexed view. step() returns a positive match's text-end
  index, or None. waiting means the next comparison needs another character.
  This class never performs a whole-pattern comparison as a hidden step.
  """
  def __init__(self, pattern, text, *, k=8):
    if not len(pattern) or k < 4:
      raise ValueError("a nonempty pattern and k >= 4 are required")
    self.pattern, self.text, self.k = pattern, text, k
    self.preparation = IndexedJob(pattern, decomposition(len(pattern), k))
    self.part = None
    self.position = self.matched = self.checked = 0
    self.prefix_ok = True
    self.quota = 0
    self.mode = "compare"
    self.steps = 0

  @property
  def waiting(self):
    return self.part is not None and self.mode == "compare" and \
      self.position + self.part.cut + self.matched >= len(self.text)

  def _after_prefix_work(self):
    self.mode = "compare"
    if self.matched != len(self.pattern) - self.part.cut:
      return None
    if self.prefix_ok and self.checked != self.part.cut:
      raise AssertionError("prefix verifier missed the full-match deadline")
    self.mode = "shift"
    return self.position + len(self.pattern) if self.prefix_ok else None

  def step(self):
    if self.waiting:
      return None
    self.steps += 1
    if self.part is None:
      self.preparation.step()
      if self.preparation.done:
        self.part = self.preparation.result
      return None
    cut, period, reach = self.part.cut, self.part.period, self.part.reach
    if self.mode == "shift":
      if period is not None and self.k * period <= self.matched <= reach:
        self.position += period
        self.matched -= period
      else:
        self.position += shift_without_period(self.matched, self.k)
        self.matched = 0
      self.checked, self.prefix_ok, self.mode = 0, True, "compare"
      return None
    if self.mode == "prefix":
      if self.pattern[self.checked] != self.text[self.position + self.checked]:
        self.prefix_ok = False
      self.checked += 1
      self.quota -= 1
      if not self.prefix_ok or self.checked == cut or not self.quota:
        return self._after_prefix_work()
      return None
    if self.pattern[cut + self.matched] != self.text[self.position + cut + self.matched]:
      self.mode = "shift"
      return None
    self.matched += 1
    if self.prefix_ok and self.checked < cut:
      self.mode, self.quota = "prefix", 2
      return None
    return self._after_prefix_work()


def palindrome_job(word, lower, upper, *, k=8):
  return IndexedJob(PalindromeView(word), palindrome_flags(len(word), lower, upper, k))
