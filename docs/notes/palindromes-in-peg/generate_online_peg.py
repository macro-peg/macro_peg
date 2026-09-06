"""Generate the derived FIFO/whole-round PAL candidate on original input.

No input expansion, recognition budget, or word-length cap is accepted.
The compilation memory limit is a host resource limit only. Output is renamed
into place only after every ordinary PEG rule has been emitted. Source
correctness remains subject to the contracts in TRANSLATION_STRATEGY.md.
"""
import argparse
import gc
import hashlib
import json
from pathlib import Path
import resource
import signal
import time

from galil_clock import derive
from scaffold_circuit_galil import build_online
from scaffold_event_buffer import buffer_source, pack_service
from symbolic_sca2peg import share_expressions
import scaffold_artifact


def source_signature():
  root = Path(__file__).parent
  digest = hashlib.sha256()
  extra = {"scaffold_event_buffer.py", "symbolic_sca2peg.py", "galil_clock.py", "read_blocks.py"}
  for path in sorted(root.glob("*.py")):
    if path.name.startswith(("scaffold_circuit", "fpp_", "dp_")) or path.name in extra:
      digest.update(path.name.encode() + b"\0" + path.read_bytes() + b"\0")
  return digest.hexdigest()


def generate(output, quantum, report, shared_cells=False, wrapper_cache=None,
             wrapper_only=False, rebuild_wrapper=False, lazy_round=False):
  timing = derive(quantum)
  report.update(timing=vars(timing), service=timing.service,
                slots=timing.service + 1, shared_cells=shared_cells, lazy_round=lazy_round)
  fingerprint = dict(signature=source_signature(), quantum=quantum, shared_cells=shared_cells)
  if wrapper_cache and wrapper_cache.exists() and not rebuild_wrapper:
    report["stage"] = "load finite FIFO artifact"
    print(json.dumps(report), flush=True)
    wrapper, metadata = scaffold_artifact.read(wrapper_cache)
    if metadata != fingerprint:
      raise ValueError("FIFO artifact differs from this source; use --rebuild-wrapper or another path")
  else:
    report["stage"] = "online source"
    print(json.dumps(report), flush=True)
    with share_expressions():
      circuit, source = build_online(quantum, shared_cells=shared_cells)
    ports = (circuit._label("online.ready", 0), circuit._label("online.event", 0), source.accepting)
    report["source"] = dict(labels=len(source.labels), pointers=len(source.pointers))
    report["stage"] = "finite FIFO wrapper"
    print(json.dumps(report), flush=True)
    del circuit
    gc.collect()
    with share_expressions():
      buffer_circuit, wrapper = buffer_source(source, *ports)
    del buffer_circuit, source
    gc.collect()
    if wrapper_cache:
      report["stage"] = "save finite FIFO artifact"
      print(json.dumps(report), flush=True)
      report["artifact_nodes"] = scaffold_artifact.write(wrapper, wrapper_cache, fingerprint)
  report["wrapper"] = dict(labels=len(wrapper.labels), pointers=len(wrapper.pointers))
  if wrapper_only:
    report.update(stage="finite FIFO artifact saved", artifact=str(wrapper_cache), emitted=False)
    return
  report["stage"] = "whole input round"
  print(json.dumps(report), flush=True)
  with share_expressions():
    packed = pack_service(wrapper, timing.service, lazy=lazy_round)
  del wrapper
  gc.collect()
  report["packed"] = dict(labels=len(packed.labels), pointers=len(packed.pointers))
  report["stage"] = "ordinary PEG emission"
  print(json.dumps(report), flush=True)
  temporary = output.with_suffix(output.suffix + ".partial")
  with temporary.open("w") as stream:
    for count, rule in enumerate(packed.iter_rules(), 1):
      stream.write(rule + "\n")
  temporary.replace(output)
  report.update(stage="emitted", rules=count, bytes=output.stat().st_size,
                output=str(output), emitted=True, raw_input_matches="not run")


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("output", type=Path)
  parser.add_argument("--quantum", type=int, default=64)
  parser.add_argument("--memory-mib", type=int, default=16384)
  parser.add_argument("--report", type=Path)
  parser.add_argument("--shared-instruction-cells", action="store_true",
                      help="share mutually exclusive tape-move cells in finite programs")
  parser.add_argument("--wrapper-cache", type=Path,
                      help="save/load the exact finite FIFO DAG, with source fingerprint checking")
  parser.add_argument("--wrapper-only", action="store_true",
                      help="prepare the finite FIFO artifact without starting PEG generation")
  parser.add_argument("--rebuild-wrapper", action="store_true")
  parser.add_argument("--lazy-round", action="store_true")
  args = parser.parse_args()
  if args.memory_mib < 1:
    parser.error("positive compilation memory limit required")
  if args.wrapper_only and not args.wrapper_cache:
    parser.error("--wrapper-only requires --wrapper-cache")
  derive(args.quantum)
  resource.setrlimit(resource.RLIMIT_AS, (args.memory_mib * 1024 * 1024,) * 2)
  report, started = {}, time.monotonic()
  def interrupted(signum, frame):
    raise KeyboardInterrupt
  signal.signal(signal.SIGTERM, interrupted)
  try:
    generate(args.output, args.quantum, report, args.shared_instruction_cells,
             args.wrapper_cache, args.wrapper_only, args.rebuild_wrapper, args.lazy_round)
  except MemoryError:
    report.update(status="compilation memory limit exceeded", emitted=False)
    raise SystemExit(1)
  except KeyboardInterrupt:
    report.update(status="compilation interrupted", emitted=False)
    raise SystemExit(130)
  finally:
    report["seconds"] = time.monotonic() - started
    report["peak_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    if args.report:
      args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
  main()
