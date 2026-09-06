import itertools
import random
import unittest

from delayed_pal import DelayedPal, Window, recognize


class DelayedPalTest(unittest.TestCase):
  def check_prefixes(self, word):
    machine = DelayedPal()
    for size, char in enumerate(word, 1):
      self.assertEqual(machine.feed(char), word[:size] == word[:size][::-1], (word[:80], size))
      self.assertLessEqual(len(machine.stages), 2)
    return machine

  def test_all_binary_prefixes(self):
    self.assertTrue(recognize(""))
    for size in range(13):
      for letters in itertools.product("ab", repeat=size):
        self.check_prefixes("".join(letters))

  def test_stage_boundaries_and_periodic_patterns(self):
    for width in (16, 32, 64, 128, 256, 512):
      left = ("a" * 16 + "b") * (width // 17) + "a" * (width % 17)
      self.check_prefixes(left + left[::-1])
      self.check_prefixes(left + "a" + left[::-1])
      self.check_prefixes(left + "b" + left[::-1])
    for word in ("a" * 2048, "ab" * 1024, ("a" * 64 + "b") * 64):
      machine = self.check_prefixes(word)
      self.assertGreater(machine.statistics()["completed_jobs"], 20)

  def test_long_random_palindromes_and_perturbations(self):
    rng = random.Random(810)
    for _ in range(100):
      left = "".join(rng.choice("ab") for _ in range(rng.randrange(1, 300)))
      middle = rng.choice(("", "a", "b"))
      palindrome = left + middle + left[::-1]
      self.check_prefixes(palindrome)
      place = rng.randrange(len(left))
      changed = palindrome[:place] + ("b" if palindrome[place] == "a" else "a") + palindrome[place + 1:]
      self.assertFalse(recognize(changed))

  def test_windows_cannot_read_future_input(self):
    data = list("abba")
    fixed = Window(data, 1, 3)
    growing = Window(data, 1)
    reverse = Window(data, 0, 4, True)
    data.extend("ab")
    self.assertEqual(len(fixed), 2)
    self.assertEqual(len(growing), 5)
    self.assertEqual("".join(reverse[i] for i in range(len(reverse))), "abba")
    with self.assertRaises(IndexError):
      _ = fixed[2]
    with self.assertRaises(ValueError):
      Window(data, 0, 7)
    with self.assertRaises(ValueError):
      DelayedPal().feed("#")


if __name__ == "__main__":
  unittest.main()
