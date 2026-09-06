"""External, coordinate-based observations of the online source contracts.

Nothing in this module participates in the recognizer's decisions. It decodes
immutable stacks only at algorithm boundaries, using the already offered input
as an independent oracle. Passing finite traces is not a proof of the source.
"""
from collections import Counter


def depth(root, name):
  node = root.ptr[name + ".top"]
  creator, slot = root.label[name + ".tname"], root.label[name + ".tslot"]
  count = 0
  while node is not None:
    key = f"{creator}.{slot}."
    creator, slot = node.label[key + "bname"], node.label[key + "bslot"]
    node = node.ptr[key + "below"]
    count += 1
  return count


def number(root, name):
  return depth(root, name + ".pos") - depth(root, name + ".neg")


def place(root, name):
  return 2 * depth(root, name + ".l") - int(not root.label[name + ".gap"])


def interval(word, left, right):
  assert 0 <= left <= right <= 2 * len(word), (word, left, right)
  return tuple(None if p == 0 else "s" if p % 2 == 0 else word[p // 2]
               for p in range(left, right + 1))


def palindrome(value):
  return value == value[::-1]


def small_live_period(word, center, lower, right):
  """Diagnostic sufficient witness to a live chain forbidden at main entry.

  A palindromic block of width 2h ending at C determines its alternating
  reflection extension. Check whether that extension still reaches R. This
  observer does not replace the paper's chain lemmas or test maximal chains.
  """
  for h in range(1, min(lower, (center - 1) // 2) + 1):
    block = interval(word, center - 2 * h, center)
    if not palindrome(block):
      continue
    expected = tuple(block[2 * h - min(i % (2 * h), 2 * h - i % (2 * h))]
                     for i in range(right - center + 1))
    if interval(word, center, right) == expected:
      return h
  return None


class ContractAudit:
  def __init__(self):
    self.previous = self.move = self.replay = self.shift = None
    self.counts = Counter()
    self.rows = []
    self.last_output = None

  def record(self, event, root, **values):
    self.counts[event] += 1
    row = dict(event=event, tick=root.t, **values)
    self.rows.append(row)
    return row

  def observe(self, source, word, result):
    root, old = source.vm.top, self.previous
    lab = root.label
    old_mode = None if old is None else old.label["g.mode"]
    mode = lab["g.mode"]
    if (lab["sp.mode"] == "found" and
        (old is None or old.label["sp.mode"] != "found")):
      c, r = place(root, "C"), place(root, "R")
      lower, h = number(root, "sp.lo"), depth(root, "dp.t11.l")
      candidates = [step for step in range(lower + 1, (c - 1) // 4 + 1)
                    if palindrome(interval(word, c - 4 * step, c))
                    and palindrome(interval(word, c - 2 * step, c))]
      row = self.record("dp_found", root, C=c, R=r, h=h, lower=lower)
      assert candidates and h == min(candidates), row
      assert r - c < 2 * h, row
    if old is None or result.events["replays"] or result.events["search_restarts"]:
      c, r, l = (place(root, name) for name in ("C", "R", "L"))
      lower = number(root, "sp.lo")
      row = self.record("main", root, C=c, R=r, L=l, lower=lower)
      assert lower <= r - c and 3 * (r - c) <= 5 * lower, row
      assert number(root, "g.rad") == r - c and l == 2 * c - r, row
      assert palindrome(interval(word, l, r)), row
      assert small_live_period(word, c, lower, r) is None, row

    if old_mode == "scan" and mode == "copy":
      c, r, l = (place(root, name) for name in ("C", "R", "L"))
      value = interval(word, l + 1, r)
      size = max(n for n in range(1, len(value) + 1, 2)
                 if palindrome(value[-n:]))
      self.move = self.record("move", root, C=c, R=r, L=l,
                              selected=r - (size - 1) // 2)
      assert l == 2 * c - r and number(root, "g.rad") == r - c, self.move
      assert number(root, "g.rem") == len(value), self.move

    if result.events["replays"]:
      assert self.move is not None
      c, rr = place(root, "C"), self.move["R"]
      row = self.record("replay_start", root, C=c, RR=rr)
      assert c == self.move["selected"], (self.move, row)
      assert 4 * (c - self.move["C"]) > rr - self.move["C"] - 1, row
      assert self.move["C"] < c <= rr, row
      assert place(root, "R") == place(root, "L") == c, row
      assert number(root, "g.rad") == 0 and number(root, "g.replay") == rr - c, row
      self.move, self.replay = None, row

    if self.replay is not None and mode == "scan" and not lab["g.replaying"]:
      c, rr = self.replay["C"], self.replay["RR"]
      row = self.record("replay_return", root, C=c, RR=rr)
      assert place(root, "R") == rr and place(root, "C") == c, row
      assert place(root, "L") == 2 * c - rr, row
      assert number(root, "g.rad") == rr - c, row
      assert number(root, "g.len") == 2 * (rr - c) + 1, row
      assert number(root, "g.replay") == 0, row
      self.replay = None

    if result.events["chain_shifts"]:
      c, r, l = (place(root, name) for name in ("C", "R", "L"))
      h = number(root, "ch.h")
      self.shift = self.record("shift", root, C=c, R=r, L=l, h=h)
      assert h > 0 and number(root, "g.rem") == h, self.shift
      assert lab["ch.phase"] == 4 and number(root, "ch.lag") == 0, self.shift

    if old_mode == "shift" and mode == "scan":
      assert self.shift is not None
      c, r, l, h = (self.shift[key] for key in ("C", "R", "L", "h"))
      row = self.record("shift_return", root, C=c + h, R=r, h=h)
      assert place(root, "C") == c + h and place(root, "L") == l + 2 * h, row
      assert place(root, "R") == r and number(root, "g.rad") == r - c - h, row
      assert number(root, "g.len") == 2 * (r - c - h) + 1, row
      assert number(root, "ch.cycle") == 2 * h, row
      assert palindrome(interval(word, l + 2 * h, r)), row
      self.shift = None

    if result.output is not None:
      assert result.output == int(palindrome(word)), (word, result)
      c, size = place(root, "C"), len(word)
      assert c >= size and (c == size) == bool(result.output), (word, c)
      current = self.record("output", root, size=size, value=result.output,
                             C=c, prediction=max(c - size - 1, 0))
      if self.last_output is not None:
        previous = self.last_output
        ticks, delta = root.t - previous["tick"], c - previous["C"]
        timing = source.timing
        assert delta >= 0
        assert ticks <= timing.move_slope * delta + timing.interval_overhead, current
        if result.output:
          assert ticks <= timing.predictability, (previous, current)
        gain = current["prediction"] - previous["prediction"]
        assert (gain + 2) * timing.predictability >= ticks, (previous, current)
      self.last_output = current
    self.previous = root
