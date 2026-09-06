"""Emit the complete windowed PAL source as an ordinary binary PEG.

Uses only the derived DEFAULT_BATCH schedule. There is no input expansion,
word-length parameter, runtime callback, or empirical rate override.
Direct emission is the default; whole-source constant propagation is optional.
"""
import argparse
import gc
import json
from pathlib import Path
import time

from gs_batch_clock import DEFAULT_BATCH
from scaffold_window_pal import build
from scaffold_optimize import optimize
from symbolic_sca2peg import share_expressions
from scaffold_artifact import read as read_checkpoint, write as write_checkpoint


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("output", type=Path)
  parser.add_argument("--checkpoint", type=Path, help="save the complete finite source before rewriting")
  parser.add_argument("--resume", type=Path, help="load a source checkpoint made by this generator")
  rewriting = parser.add_mutually_exclusive_group()
  rewriting.add_argument("--optimize", dest="skip_optimize", action="store_false",
                         help="apply optional whole-source constant propagation")
  rewriting.add_argument("--skip-optimize", dest="skip_optimize", action="store_true",
                         help="emit directly without constant propagation (default)")
  parser.set_defaults(skip_optimize=True)
  args = parser.parse_args()
  start = time.monotonic()
  collecting = gc.isenabled()
  gc.disable()
  try:
    metadata = dict(kind="window-pal", k=DEFAULT_BATCH.k,
                    matching=DEFAULT_BATCH.matching, flags=DEFAULT_BATCH.flags)
    if args.resume:
      machine, saved_metadata = read_checkpoint(args.resume)
      if saved_metadata != metadata:
        raise ValueError("checkpoint is not the complete default window PAL source")
    else:
      with share_expressions():
        circuit, controller, machine = build()
      del circuit, controller
    print(json.dumps(dict(phase="built", seconds=time.monotonic() - start,
                          labels=len(machine.labels), pointers=len(machine.pointers))), flush=True)
    # Release construction cycles once, then leave collection disabled while
    # traversing the immutable expression DAG for rewriting and emission.
    gc.collect()
    if args.checkpoint:
      nodes = write_checkpoint(machine, args.checkpoint, metadata)
      print(json.dumps(dict(phase="checkpointed", file=str(args.checkpoint), nodes=nodes,
                            seconds=time.monotonic() - start)), flush=True)
    if not args.skip_optimize:
      machine, stats = optimize(machine)
      print(json.dumps(dict(phase="optimized", seconds=time.monotonic() - start, **stats)), flush=True)
    with args.output.open("w", encoding="utf-8") as output:
      count = 0
      for rule in machine.iter_rules():
        output.write(rule + "\n")
        count += 1
    print(json.dumps(dict(phase="emitted", file=str(args.output), rules=count,
                          bytes=args.output.stat().st_size, seconds=time.monotonic() - start,
                          matching=DEFAULT_BATCH.matching, flags=DEFAULT_BATCH.flags)), flush=True)
  finally:
    if collecting: gc.enable()


if __name__ == "__main__":
  main()
