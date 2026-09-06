import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from verify_window_pal import verify


class VerifyWindowPALTest(unittest.TestCase):
  def setUp(self):
    self.directory = tempfile.TemporaryDirectory()
    self.addCleanup(self.directory.cleanup)
    root = Path(self.directory.name)
    self.grammar, self.runner, self.log = root / "input.peg", root / "runner", root / "run.log"
    self.grammar.write_text('S <- !.\n')
    self.manifest = self.log.with_suffix(".json")
    self.manifest.write_text('{"status":"passed","checked":999}\n')

  def run_reports(self, reports, code=0):
    process = Mock(stdout=io.StringIO(reports), returncode=code)
    process.wait.return_value = code
    process.poll.return_value = code
    with patch("verify_window_pal.subprocess.Popen", return_value=process), \
         patch("sys.stdout", new_callable=io.StringIO):
      return verify(self.grammar, self.runner, ["", "ab"], self.log, 1)

  def report(self):
    return json.loads(self.manifest.read_text())

  def test_success_requires_all_unchanged_inputs(self):
    result = self.run_reports('match\t""\ttrue\tcharacters=0\trepeat=1\n'
                              'match\t"ab"\tfalse\tcharacters=2\trepeat=1\n')
    self.assertEqual((result["status"], result["checked"]), ("passed", 2))
    self.assertEqual(len(result["sha256"]), 64)

  def test_counterexample_replaces_old_success(self):
    with self.assertRaises(AssertionError):
      self.run_reports('match\t""\ttrue\tcharacters=0\trepeat=1\n'
                       'match\t"ab"\ttrue\tcharacters=2\trepeat=1\n')
    result = self.report()
    self.assertEqual((result["status"], result["checked"], result["current_word"]),
                     ("failed", 1, "ab"))
    self.assertIn('"ab"', self.log.read_text())

  def test_truncated_or_repeated_input_reports_fail(self):
    for reports in ('', 'match\t""\ttrue\tcharacters=0\trepeat=2\n'):
      with self.subTest(reports=reports), self.assertRaises((RuntimeError, AssertionError)):
        self.run_reports(reports)
      self.assertEqual(self.report()["status"], "failed")

  def test_launch_failure_replaces_old_success(self):
    with patch("verify_window_pal.subprocess.Popen", side_effect=FileNotFoundError("runner missing")), \
         self.assertRaises(FileNotFoundError):
      verify(self.grammar, self.runner, [""], self.log, 0)
    self.assertEqual(self.report()["status"], "failed")
    self.assertIn("runner missing", self.report()["error"])

  def test_output_collision_does_not_overwrite_grammar(self):
    before = self.grammar.read_bytes()
    with self.assertRaises(ValueError):
      verify(self.grammar, self.runner, [""], self.grammar, 0)
    self.assertEqual(self.grammar.read_bytes(), before)


if __name__ == "__main__":
  unittest.main()
