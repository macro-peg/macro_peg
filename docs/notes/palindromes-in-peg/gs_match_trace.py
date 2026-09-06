"""External work-trace generator for testing the emitted matcher PEG.

This is a test protocol, never the final PAL input transformation. Each
source instruction uses at most one queue pop, followed by three work units.
The finite broadcast visits one queue at a time under the same schedule.
"""
from gs_match_heads import StreamingMatcher, compile_matcher
from gs_head_liveness import analyze_readers


def trace(prefix, text, program=None):
  if not prefix or set(prefix + text) - set("ab"):
    raise ValueError("nonempty binary prefix and binary text required")
  program = compile_matcher() if program is None else program
  observer = StreamingMatcher(prefix[::-1], program)
  # One push and three work units per queue, followed by the broadcast exit.
  broadcast = 4 * (1 + analyze_readers(program).registers) + 1
  parts = [char + "." * 5 for char in prefix]
  parts += ["!", "." * (4 * observer.drain())]
  for char in text:
    observer.append(char)
    parts += [char, "." * (broadcast + 4 * observer.drain())]
  return "".join(parts)
