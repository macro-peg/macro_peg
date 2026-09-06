"""Superseded fixed-arrival experiment; not the current PAL translation.

Use generate_online_peg.py for the derived FIFO/whole-round pipeline. This
file remains only to reproduce earlier experiments and their failures.

The default output is an ordinary PEG on unchanged binary strings. Its
fixed-work constants are experimental, not a claim of correctness for PAL.
--expanded-only stops at the intermediate PEG, whose input repeats each
letter `budget` times; this is explicitly not the final requested artifact.
"""
import argparse
import gc
from pathlib import Path
import time

from phase_peg import Grammar, inverse_repeat
from peg_file import FileGrammar
from scaffold_circuit_galil import build
from symbolic_sca2peg import share_expressions


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("output", type=Path)
  parser.add_argument("--quantum", type=int, default=64)
  parser.add_argument("--match-delay", type=int, default=256)
  parser.add_argument("--budget", type=int, default=2048)
  parser.add_argument("--raw-instructions", action="store_true")
  parser.add_argument("--omit-invariant-monitors", action="store_true",
                      help="experimental release build; omits internal assertion monitors")
  parser.add_argument("--expanded-only", action="store_true")
  parser.add_argument("--match", nargs="*", default=None)
  args = parser.parse_args()
  started = time.monotonic()
  with share_expressions():
    circuit, machine = build(args.quantum, args.match_delay, args.budget,
                             coarse=not args.raw_instructions,
                             check_invariants=not args.omit_invariant_monitors)
  print(f"built {len(machine.labels)} labels, {len(machine.pointers)} pointers", flush=True)
  intermediate = args.output if args.expanded_only else args.output.with_suffix(".expanded.peg")
  temporary = intermediate.with_suffix(intermediate.suffix + ".partial")
  with temporary.open("w") as stream:
    for count, rule in enumerate(machine.iter_rules(), 1):
      stream.write(rule + "\n")
  temporary.replace(intermediate)
  print(f"emitted {intermediate}: {count} rules, {intermediate.stat().st_size} bytes", flush=True)
  del circuit, machine
  gc.collect()
  if not args.expanded_only:
    source = inverse_repeat(intermediate.read_text(), args.budget)
    args.output.write_text(source)
    print(f"emitted {args.output}: {len(source.splitlines())} rules, {len(source.encode())} bytes", flush=True)
  if args.match is not None:
    grammar = FileGrammar(args.output)
    mismatches = 0
    for word in args.match:
      if any(char not in "ab" for char in word):
        parser.error("match examples must be binary strings over a,b")
      supplied = "".join(char * args.budget for char in word) if args.expanded_only else word
      actual = grammar.accepts(supplied, compact=True)
      expected = word == word[::-1]
      print(f"{word!r}: PEG={actual}, palindrome={expected}", flush=True)
      mismatches += actual != expected
    grammar.close()
    if mismatches:
      raise SystemExit(f"{mismatches} candidate mismatches")
  print(f"finished in {time.monotonic() - started:.1f}s", flush=True)


if __name__ == "__main__":
  main()
