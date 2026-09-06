"""External loading/work traces for testing the finite mirror-flag worker."""
from gs_flag_heads import compile_flags, FlagVM
from gs_dual_flags import compile_dual_flags, DualFlagVM


def trace(word, lower, program=None, dual=False):
  if set(word) - set("ab") or not 0 <= lower <= len(word):
    raise ValueError("binary word and valid lower endpoint required")
  program = (compile_dual_flags() if dual else compile_flags()) if program is None else program
  observer = (DualFlagVM if dual else FlagVM)(word, lower, len(word), program)
  observer.run()
  parts = []
  for index, char in enumerate(word):
    if index == lower:
      parts.append("|")
    parts.append(char + "." * 4)
  if lower == len(word):
    parts.append("|")
  # One GS instruction plus at most three queue work units, then halt.
  parts.extend(("!", "." * (4 * observer.steps + 1)))
  return "".join(parts)
