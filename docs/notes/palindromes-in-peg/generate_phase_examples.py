"""Regenerate finite-phase plain PEG examples, without modifying their inputs."""
from pathlib import Path

from phase_peg import inverse_repeat


def main():
  directory = Path(__file__).parent / "generated"
  move = (directory / "sparse_preserving_move.peg").read_text()
  examples = {
    "phase_preserving_move_2.peg": (move, 2),
    "phase_preserving_move_4.peg": (move, 4),
    "phase_ordered_choice.peg": ('S = ("a" / "aa") "bb" !.;', 2),
    "phase_balanced.peg": ('S = A !.; A = "a" A "b" / "";', 3),
    "phase_repetition.peg": ('S = "a"* "bb" !.;', 2),
    "phase_greedy.peg": ('S = ("a" &"a")* "aa" !.;', 2),
  }
  for filename, (source, width) in examples.items():
    output = inverse_repeat(source, width)
    (directory / filename).write_text(output)
    print(f"{filename}: {len(output.splitlines())} rules, {len(output.encode())} bytes")


if __name__ == "__main__":
  main()
