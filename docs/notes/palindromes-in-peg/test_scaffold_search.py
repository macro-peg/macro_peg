"""Paced doubling search uses real finite FPP instructions on the scaffold."""
import unittest

from scavm import VM, SELF
from scavm_structs import Builder, CounterView, emit
from scaffold_input import InputHead
from scaffold_places import PlaceHead
from scaffold_search import SearchView
from dp_finite import build_dp_program, OUTPUT


def exercise(word, lower=0, pace=None, schedule=None, places=False):
  vm = VM()
  kernel = build_dp_program("abs" if places else "ab")
  head_type = PlaceHead if places else InputHead
  # Explicit input and lower-bound fixtures. Three independent input heads
  # receive each real input; no Python index is used by the search transition.
  for symbol in word:
    vm.begin()
    b = Builder()
    b.label["input"] = symbol
    center, walk = [head_type(vm, vm.top, b, name) for name in ("c", "w")]
    for head in (center, walk):
      head.append(SELF)
      head.right()
      if places:
        head.right()
    radius = CounterView(vm, vm.top, b, "radius")
    bound = CounterView(vm, vm.top, b, "bound")
    job = SearchView(vm, vm.top, b, kernel, center, walk, radius)
    for item in (center, walk, radius, bound, job):
      item.finalize()
    emit(vm, b)
  for _ in range(lower):
    vm.begin()
    b = Builder()
    b.label["input"] = "a"
    center, walk = [head_type(vm, vm.top, b, name) for name in ("c", "w")]
    radius = CounterView(vm, vm.top, b, "radius")
    bound = CounterView(vm, vm.top, b, "bound")
    bound.inc()
    job = SearchView(vm, vm.top, b, kernel, center, walk, radius)
    for item in (center, walk, radius, bound, job):
      item.finalize()
    emit(vm, b)
  modes, match_events = set(), 0
  # Observer controls the pace of synthetic match events. It does not choose
  # the answer, stage span, scan position or candidate inside SearchView.
  for tick in range(10000 * (len(word) + lower + 1)):
    vm.begin()
    b = Builder()
    b.label["input"] = "a"
    center, walk = [head_type(vm, vm.top, b, name) for name in ("c", "w")]
    radius = CounterView(vm, vm.top, b, "radius")
    bound = CounterView(vm, vm.top, b, "bound")
    job = SearchView(vm, vm.top, b, kernel, center, walk, radius)
    if tick == 0:
      job.start(bound)
    advance = (schedule(job, match_events) if schedule else
               (pace is None and job.mode == "wait") or (pace and tick % pace == pace - 1))
    if advance:
      job.advance_match()
      match_events += 1
    job.step()
    modes.add(job.mode)
    finished = job.mode in ("found", "missed")
    for item in (center, walk, radius, bound, job):
      item.finalize()
    emit(vm, b)
    if finished:
      break
  else:
    raise AssertionError("search watchdog")
  found = job.mode == "found"
  answer = 0
  if found:
    # Decode unary OUTPUT only in the observer, one tape move per node.
    while True:
      vm.begin()
      b = Builder()
      b.label["input"] = "a"
      center, walk = [head_type(vm, vm.top, b, name) for name in ("c", "w")]
      radius = CounterView(vm, vm.top, b, "radius")
      bound = CounterView(vm, vm.top, b, "bound")
      job = SearchView(vm, vm.top, b, kernel, center, walk, radius)
      tape = job.program.tapes[OUTPUT]
      if tape.read() == "^":
        break
      answer += 1
      tape.move(-1)
      for item in (center, walk, radius, bound, job):
        item.finalize()
      emit(vm, b)
  return answer if found else None, modes, {**vm.stats(), "match_events": match_events}


class ScaffoldSearchTest(unittest.TestCase):
  def test_search_reads_virtual_places_from_unmodified_binary_input(self):
    for word in ("aaaaaa", "ababababab", "abbabbabba"):
      view = "".join(symbol + "s" for symbol in word)
      expected = next((h for h in range(1, (len(view) - 1) // 4 + 1)
                       if view[-2*h-1:] == view[-2*h-1:][::-1]
                       and view[-4*h-1:] == view[-4*h-1:][::-1]), None)
      result, _, _ = exercise(word, places=True)
      self.assertEqual(result, expected, word)

  def test_finite_search_matches_suffix_double_palindrome_definition(self):
    for word, lower in (("a", 0), ("abba", 0), ("aaaaa", 0),
                        ("a" * 17, 2), ("abba" * 5, 0),
                        ("abc".replace("c", "ab") * 5, 1),
                        ("a" * 17 + "b", 0)):
      expected = next((h for h in range(lower + 1, (len(word) - 1) // 4 + 1)
                       if word[-2*h-1:] == word[-2*h-1:][::-1]
                       and word[-4*h-1:] == word[-4*h-1:][::-1]), None)
      result, _, stats = exercise(word, lower)
      self.assertEqual(result, expected, (word, lower))
      self.assertLess(stats["radius"], 500)

  def test_missed_stages_wait_for_match_before_doubling(self):
    result, modes, _ = exercise("a" * 17 + "b")
    self.assertIsNone(result)
    self.assertIn("wait", modes)
    self.assertIn("double", modes)

  def test_search_can_overlap_slow_match_steps(self):
    result, modes, stats = exercise("a" * 65, lower=8, pace=1024)
    self.assertEqual(result, 9)
    self.assertIn("run", modes)
    self.assertGreater(stats["match_events"], 0)

  def test_nonfinal_miss_on_exact_deadline_starts_doubling_without_extra_wait(self):
    def schedule(job, events):
      return ((job.mode == "run" and
               (events == 0 or (events == 1 and
                job.program.program.code[job.program.pc][0] == "halt")))
              or job.mode == "wait")
    result, modes, _ = exercise("a" * 17 + "b", schedule=schedule)
    self.assertIsNone(result)
    self.assertIn("double", modes)

  def test_search_rejects_actual_deadline_overrun(self):
    with self.assertRaisesRegex(RuntimeError, "stage deadline"):
      exercise("a" * 17 + "b", pace=1)


if __name__ == "__main__":
  unittest.main()
