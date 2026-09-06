"""Two overlapping dyadic stages for a directly clocked PAL experiment.

This accepts original words, but is still an indexed reference machine, not
an ordinary PEG. Rates below bound yielded indexed operations. Local-head
lowering and its own service bound must precede SCA/PEG compilation.
"""
from gs_events import PatternMatcher, palindrome_job


MATCH_RATE = 80
JOB_RATE = 1024


class Window:
  def __init__(self, data, start, end=None, reverse=False):
    self.data, self.start, self.end, self.reverse = data, start, end, reverse
    if start < 0 or end is not None and not start <= end <= len(data):
      raise ValueError("window must contain only arrived characters")
    if reverse and end is None:
      raise ValueError("a reversed window needs a fixed endpoint")

  def __len__(self):
    return (len(self.data) if self.end is None else self.end) - self.start

  def __getitem__(self, index):
    if not 0 <= index < len(self):
      raise IndexError(index)
    return self.data[self.end - 1 - index if self.reverse else self.start + index]


class Stage:
  def __init__(self, data, width):
    self.data, self.width = data, width
    self.matcher = PatternMatcher(Window(data, 0, width, True), Window(data, width))
    self.job = None
    self.job_index = None
    self.job_flags = None
    self.results = [None] * 4
    self.job_steps = self.match_steps = 0
    self.jobs_completed = 0

  def tick(self, now):
    width, half = self.width, self.width // 2
    if now > width and (now - width) % half == 0:
      batch = (now - width) // half - 1
      if batch < 4:
        if self.job is not None:
          raise AssertionError("offline palindrome job overran its release interval")
        self.job_index, self.job_flags = batch, []
        size = (batch + 1) * half
        self.job = palindrome_job(Window(self.data, width, width + size), batch * half, size)
    if self.job is not None:
      for _ in range(JOB_RATE):
        event = self.job.step()
        self.job_steps += 1
        if event is not None and event[0] == "flag":
          self.job_flags.append(event[1])
        if self.job.done:
          if len(self.job_flags) != half:
            raise AssertionError("incomplete palindrome flag block")
          self.results[self.job_index] = self.job_flags
          self.job = self.job_flags = None
          self.jobs_completed += 1
          break
    matched = False
    for _ in range(MATCH_RATE):
      if self.matcher.waiting:
        break
      endpoint = self.matcher.step()
      self.match_steps += 1
      if endpoint is not None:
        if endpoint + width != now:
          raise AssertionError("positive pattern match missed its arrival deadline")
        matched = True
    if now < 2 * width:
      return None
    batch = (now - 2 * width) // half
    flags = self.results[batch]
    if not flags:
      raise AssertionError("middle palindrome flags missed their deadline")
    middle = flags.pop()
    return matched and middle


class DelayedPal:
  """Feed one binary character; report that exact prefix's PAL membership."""
  def __init__(self):
    self.data, self.stages = [], []
    self.next_width = 2
    self.retired_job_steps = self.retired_match_steps = 0
    self.retired_jobs = 0

  def feed(self, char):
    if char not in ("a", "b"):
      raise ValueError("binary input required")
    self.data.append(char)
    now = len(self.data)
    while self.stages and now == 4 * self.stages[0].width:
      retired = self.stages.pop(0)
      self.retired_job_steps += retired.job_steps
      self.retired_match_steps += retired.match_steps
      self.retired_jobs += retired.jobs_completed
    if now == self.next_width:
      self.stages.append(Stage(self.data, self.next_width))
      self.next_width *= 2
    result = True if now < 2 else self.data[0] == char if now < 4 else None
    for stage in self.stages:
      answer = stage.tick(now)
      if answer is not None:
        if result is not None:
          raise AssertionError("overlapping active answer intervals")
        result = answer
    if len(self.stages) > 2 or result is None:
      raise AssertionError("dyadic stage coverage failed")
    return result

  def statistics(self):
    return dict(characters=len(self.data),
                job_steps=self.retired_job_steps + sum(s.job_steps for s in self.stages),
                match_steps=self.retired_match_steps + sum(s.match_steps for s in self.stages),
                completed_jobs=self.retired_jobs + sum(s.jobs_completed for s in self.stages),
                live_stages=len(self.stages))


def recognize(word):
  machine, answer = DelayedPal(), True
  for char in word:
    answer = machine.feed(char)
  return answer
