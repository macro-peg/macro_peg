"""Independent integer-coordinate observer of the fixed batch PAL schedule."""
import itertools
import random
import unittest

from gs_batch_clock import DEFAULT_BATCH
from gs_match_heads import StreamingMatcher, compile_matcher
from gs_dual_flags import DualFlagVM, compile_dual_flags


class ObserverStage:
  def __init__(self, word, matching, flagging):
    self.width = len(word)
    self.matching = StreamingMatcher(word[::-1], matching)
    self.flagging_program = flagging
    self.job, self.batch = None, None
    self.results = [None] * 4

  def feed(self, word):
    width, half, now = self.width, self.width // 2, len(word)
    if now > width: self.matching.append(word[-1])
    if now > width and (now - width) % half == 0:
      batch = (now - width) // half - 1
      if batch < 4:
        assert self.job is None
        self.batch = batch
        self.job = DualFlagVM(word[width:], batch * half, now - width, self.flagging_program)
    # Existing result consumption precedes this round's service, as it does
    # in the emitted source, so a release-boundary overrun cannot be hidden.
    middle = None
    if now >= 2 * width:
      result = self.results[(now - 2 * width) // half]
      assert result
      middle = result.pop()
    if self.job is not None:
      for _ in range(DEFAULT_BATCH.flags):
        if self.job.done:
          self.results[self.batch] = self.job.flags[:]
          assert len(self.job.flags) == half
          self.job = None
          break
        self.job.step()
    matched = False
    for _ in range(DEFAULT_BATCH.matching):
      output = self.matching.step()
      if output is not None:
        assert output + width == now
        matched = True
    return None if middle is None else middle and matched


def recognize_all(word, matching, flagging):
  stages, data, answer = [], "", True
  for char in word:
    data += char
    now = len(data)
    stages = [stage for stage in stages if now < 4 * stage.width]
    if now >= 2 and now & (now - 1) == 0:
      stages.append(ObserverStage(data, matching, flagging))
    answer = True if now == 1 else data[0] == data[-1] if now < 4 else None
    for stage in stages:
      result = stage.feed(data)
      if result is not None:
        assert answer is None
        answer = result
    assert answer is not None
    assert answer == (data == data[::-1]), data
  return answer


class BatchPALTest(unittest.TestCase):
  def test_prefix_answers_and_release_deadlines(self):
    matching, flagging = compile_matcher(unit=False), compile_dual_flags(unit=False)
    words = ["".join(w) for n in range(9) for w in itertools.product("ab", repeat=n)]
    rng = random.Random(1407)
    words += ["".join(rng.choice("ab") for _ in range(n)) for n in (32, 64, 128, 256, 512) for _ in range(3)]
    words += ["a" * 512, "ab" * 512, ("a" * 8 + "b") * 64 + "b"]
    for word in words:
      self.assertEqual(recognize_all(word, matching, flagging), word == word[::-1], word[:30])


if __name__ == "__main__":
  unittest.main()
