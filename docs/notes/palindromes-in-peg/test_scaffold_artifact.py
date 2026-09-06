"""Check that compiler checkpoints preserve both equations and behavior."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from scaffold_artifact import write, read
from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, NULL, symbol, old,
                              pointer, select, read as label, edge, both, either, negate, present)


class ScaffoldArtifactTest(unittest.TestCase):
  def test_data_round_trip_preserves_shared_graph_and_exact_peg(self):
    target = select(symbol("a"), pointer(("p",)), NULL)
    query = both(present(target), label(edge(target, "loop"), "out"))
    machine = Scaffold({"out": True, "shared": False},
      {"out": either(query, negate(old((), "out"))), "shared": query},
      {"p": SELF, "loop": pointer(())}, "out")
    with TemporaryDirectory() as directory:
      for suffix in (".sca", ".sca.gz"):
        path = Path(directory) / ("source" + suffix)
        count = write(machine, path, {"purpose": "round-trip", "service": 3})
        loaded, metadata = read(path)
        self.assertGreater(count, 0)
        self.assertEqual(metadata, {"purpose": "round-trip", "service": 3})
        self.assertEqual(loaded.compile(), machine.compile())
        self.assertIs(loaded.labels["out"][1], loaded.labels["shared"])
        for word in ("", "a", "b", "abba", "ababa"):
          self.assertEqual(loaded.run(word), machine.run(word))

  def test_deep_expression_graph_uses_a_flat_artifact(self):
    expression = symbol("a")
    for _ in range(5000): expression = negate(expression)
    machine = Scaffold({"out": False}, {"out": expression}, {}, "out")
    with TemporaryDirectory() as directory:
      path = Path(directory) / "deep.sca"
      write(machine, path)
      loaded, _ = read(path)
      self.assertTrue(loaded.run("a"))
      self.assertFalse(loaded.run("b"))


if __name__ == "__main__":
  unittest.main()
