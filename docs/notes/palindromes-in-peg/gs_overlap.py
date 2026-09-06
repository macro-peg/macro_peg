"""Table-free Galil--Seiferas prefix decomposition and right overlaps.

This is an indexed reference algorithm, not an SCA or a PAL PEG. Its fixed
set of integer cursors and random-access reads still need local-head lowering.
Meter counts comparisons/loop events, not machine instructions or input time.

The matching shifts come from Galil--Seiferas (1983), pp. 282--287. The
shortening overlap pass here is explained separately in GS_OVERLAP.md.
"""
from dataclasses import dataclass


@dataclass
class Meter:
  comparisons: int = 0
  events: int = 0
  stages: int = 0
  nonzero_cuts: int = 0

  def same(self, word, left, right):
    self.comparisons += 1
    return word[left] == word[right]


@dataclass(frozen=True)
class Decomposition:
  cut: int
  period: int | None
  reach: int


def shift_without_period(matched, k):
  return max(1, (matched + k - 1) // k)


def _first_period(word, start, size, k, meter, bound=None):
  """Shortest basic prefix repeated k times; stop at its first k copies.

  A bound restricts the candidate period length, not the compared input.
  In particular, deletion passes must not extend a found period to its full
  reach: doing that repeatedly on a long run would cost quadratic time.
  """
  p, q = 1, 0
  while p < size and (bound is None or p < bound):
    meter.events += 1
    while p + q < size and q < (k - 1) * p and meter.same(word, start + q, start + p + q):
      q += 1
      meter.events += 1
    if q == (k - 1) * p:
      return p, p + q
    p += shift_without_period(q, k)
    q = 0
  return None


def _second_period(word, start, size, k, first, reach, meter):
  p, q = 1, 0
  while p < size:
    meter.events += 1
    while p + q < size and meter.same(word, start + q, start + p + q):
      q += 1
      meter.events += 1
      if p + q > reach and q >= (k - 1) * p:
        return p
    if k * first <= q <= reach:
      p += first
      q -= first
    else:
      p += shift_without_period(q, k)
      q = 0
  return None


def decompose(word, size=None, *, k=4, meter=None):
  """Find word[:size] = u v with at most one basic k-prefix-period in v.

  The result's reach is measured within v. For a nonempty pattern,
  (k-1)*len(u) < size. No substring copies participate in this procedure.
  """
  if type(k) is not int or k < 4:
    raise ValueError("the decomposition requires an integer k >= 4")
  if size is None:
    size = len(word)
  if type(size) is not int or not 0 <= size <= len(word):
    raise ValueError("pattern size must be a prefix length")
  meter = Meter() if meter is None else meter
  start = 0
  while True:
    found = _first_period(word, start, size - start, k, meter)
    if found is None:
      return Decomposition(start, None, 0)
    first, reach = found
    while reach < size - start and meter.same(word, start + reach, start + reach - first):
      reach += 1
      meter.events += 1
    second = _second_period(word, start, size - start, k, first, reach, meter)
    if second is None:
      return Decomposition(start, first, reach)
    while True:
      found = _first_period(word, start, size - start, k, meter, bound=second)
      if found is None:
        break
      start += found[0]
      meter.events += 1


def iter_borders(word, *, k=4, meter=None):
  """Yield all nonempty proper border lengths, strictly decreasing.

  The input is read through len/indexing only; no failure table, substring,
  recursive call stack, or result array is constructed. Output storage is the
  caller's choice. The indexed operations are not yet local SCA operations.
  """
  if type(k) is not int or k < 4:
    raise ValueError("the overlap pass requires an integer k >= 4")
  meter = Meter() if meter is None else meter
  size = limit = len(word)
  position = 1  # Exclude the whole input in the first stage.
  while limit:
    part = decompose(word, limit, k=k, meter=meter)
    cut, period, reach = part.cut, part.period, part.reach
    meter.stages += 1
    meter.nonzero_cuts += int(cut != 0)
    minimum = max(1, 2 * cut)
    matched = 0
    while position <= limit - minimum:
      meter.events += 1
      while position + cut + matched < limit and meter.same(
          word, cut + matched, size - limit + position + cut + matched):
        matched += 1
        meter.events += 1
      if position + cut + matched == limit:
        checked = 0
        while checked < cut and meter.same(word, checked, size - limit + position + checked):
          checked += 1
          meter.events += 1
        if checked == cut:
          yield limit - position
      if period is not None and k * period <= matched <= reach:
        position += period
        matched -= period
      else:
        position += shift_without_period(matched, k)
        matched = 0
    limit = minimum - 1
    position = 0  # This smaller length has not yet been considered.


class PalindromeView:
  """An indexed view of u # reverse(u), with a fresh internal separator.

  This avoids allocating that string in the reference algorithm. It is not
  an input transformation performed by an emitted PEG; there is no such PEG
  yet. A local-head realization must implement this view too.
  """
  def __init__(self, word):
    self.word = word
    self.middle = len(word)
    self.separator = object()

  def __len__(self):
    return 2 * self.middle + 1

  def __getitem__(self, index):
    if not 0 <= index < len(self):
      raise IndexError(index)
    if index < self.middle:
      return self.word[index]
    if index == self.middle:
      return self.separator
    return self.word[2 * self.middle - index]


def iter_palindromic_prefixes(word, *, k=4, meter=None):
  """Yield nonempty palindrome-prefix lengths in decreasing order, offline."""
  yield from iter_borders(PalindromeView(word), k=k, meter=meter)
