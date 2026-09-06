"""A saved source must not silently survive a source-version change."""
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import unittest

import generate_online_peg as generator
from test_scaffold_event_buffer import source_fixture


class Ports:
  def _label(self, name, bit):
    return {"online.ready": "ready", "online.event": "event"}[name]


class GenerateOnlinePegTest(unittest.TestCase):
  def test_wrapper_checkpoint_reuses_exact_source_and_rejects_stale_source(self):
    with TemporaryDirectory() as directory, redirect_stdout(StringIO()):
      output = Path(directory) / "result.peg"
      cache = Path(directory) / "source.sca"
      with patch.object(generator, "source_signature", return_value="version-one"), \
           patch.object(generator, "build_online", return_value=(Ports(), source_fixture())):
        report = {}
        generator.generate(output, 64, report, wrapper_cache=cache, wrapper_only=True)
        self.assertTrue(cache.exists())
        self.assertFalse(output.exists())
        self.assertFalse(report["emitted"])
      with patch.object(generator, "build_online", side_effect=AssertionError("must load cache")):
        with patch.object(generator, "source_signature", return_value="version-one"):
          generator.generate(output, 64, {}, wrapper_cache=cache, wrapper_only=True)
        with patch.object(generator, "source_signature", return_value="version-two"):
          with self.assertRaisesRegex(ValueError, "differs from this source"):
            generator.generate(output, 64, {}, wrapper_cache=cache, wrapper_only=True)


if __name__ == "__main__":
  unittest.main()
