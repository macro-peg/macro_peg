import unittest

from fpp_finite import build_program, LEFT, END


class ResumeTest(unittest.TestCase):
  def test_single_instruction_pauses_preserve_full_result(self):
    p = build_program()
    self.assertTrue(hasattr(p, "execution"), "finite program needs resumable execution")
    word = "abaababa"
    z = dict(enumerate(LEFT + word + END))
    tapes = [z, dict(z), {0: "0", 1: "1", 2: "0"},
             {0: LEFT}, {0: LEFT}, {0: LEFT}, {0: LEFT}]
    e = p.execution(tapes, [0, 1, 0, 0, 0, 0, 0])
    while not e.done:
      before = e.steps
      e.step()
      self.assertEqual(e.steps, before + 1)
    full = p.run(word)
    self.assertEqual(e.result(), full)


if __name__ == "__main__":
  unittest.main()
