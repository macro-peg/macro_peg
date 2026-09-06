"""Checkpoint/restart plumbing, using a small synthetic source fixture."""
import contextlib
import gc
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_window_pal
from scaffold_optimize import optimize
from symbolic_sca2peg import Scaffold, TRUE, symbol


class GenerateWindowPALTest(unittest.TestCase):
  def invoke(self, args):
    collecting = gc.isenabled()
    with patch("sys.argv", ["generate_window_pal", *map(str, args)]), contextlib.redirect_stdout(io.StringIO()):
      generate_window_pal.main()
    self.assertEqual(gc.isenabled(), collecting)

  def test_saved_source_resumes_without_rebuilding(self):
    machine = Scaffold({"answer": True, "constant": True},
                       {"answer": symbol("a"), "constant": TRUE}, {}, "answer")
    with tempfile.TemporaryDirectory() as folder:
      original, resumed, saved = (Path(folder) / name for name in ("original.peg", "resumed.peg", "source.sca"))
      with patch("generate_window_pal.build", return_value=(object(), object(), machine)) as build:
        self.invoke([original, "--checkpoint", saved, "--skip-optimize"])
        build.assert_called_once_with()
      self.assertEqual(original.read_text(), machine.compile())
      with patch("generate_window_pal.build", side_effect=AssertionError("resume rebuilt the source")):
        self.invoke([resumed, "--resume", saved])
        self.assertEqual(resumed.read_text(), machine.compile())
        self.invoke([resumed, "--resume", saved, "--optimize"])
      expected, _ = optimize(machine)
      self.assertEqual(resumed.read_text(), expected.compile())


if __name__ == "__main__":
  unittest.main()
