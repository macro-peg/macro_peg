"""Inline private expressions in a concrete scaffold-generated PEG.

This is ordinary nonterminal inlining plus reachability. It neither executes
the source algorithm nor recognizes palindrome words. Rule bodies remain PEG
expressions, and input symbols are unchanged. The file format is the emitted
one-production-per-line S/B_n/P_n/E_n format.

The default fuses private branch wrappers. --inline-private also substitutes
single-use E rules, grouping where needed and limiting substitution depth. Recursive
label/pointer boundaries and shared expressions remain ordinary nonterminals.
"""
import argparse
from array import array
from functools import lru_cache
import json
import mmap
from pathlib import Path
import re


MISSING = (1 << 64) - 1
GROUPS = (b"S", b"B", b"P", b"E")
TOKENS = re.compile(rb'"(?:\\.|[^"\\])*"|(?P<ref>[A-Za-z_][A-Za-z_0-9]*)')
PAIR = re.compile(rb"(E_[0-9]+) (E_[0-9]+)\Z")
CHOICE = re.compile(rb"(E_[0-9]+) / (E_[0-9]+)\Z")
NEGATIVE = re.compile(rb"!(E_[0-9]+)\Z")
ATOM = re.compile(rb'(?:[!&]\s*)*(?:"(?:\\.|[^"\\])*"|[A-Za-z_][A-Za-z_0-9]*|\.)\Z')
GROUP_TOKENS = re.compile(rb'"(?:\\.|[^"\\])*"|[()/]')
RADIX = b"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"


def identifier(name):
  if name == b"S": return 0
  if len(name) < 3 or name[:1] not in GROUPS[1:] or name[1:2] != b"_" or not name[2:].isdigit():
    raise ValueError("expected a scaffold-generated rule name")
  return (int(name[2:]) << 2) | GROUPS.index(name[:1])


def references(body):
  for match in TOKENS.finditer(body):
    if match.group("ref") is not None:
      yield identifier(match.group("ref"))


def needs_group(body, before, after):
  if before.rstrip().endswith((b"!", b"&")) or after.lstrip().startswith(b"*"):
    return True
  if ATOM.fullmatch(body): return False
  depth = 0
  for match in GROUP_TOKENS.finditer(body):
    token = match.group()
    if token == b"(": depth += 1
    elif token == b")": depth -= 1
    elif token == b"/" and not depth: return True
  # Sequence associates freely in another sequence or a choice branch.
  # Existing parenthesized predicates retain their own grouping.
  return False


def compact(source, target, *, short_names=False, inline_private=False):
  source, target = Path(source), Path(target)
  if source.resolve() == target.resolve():
    raise ValueError("source and destination must be distinct files")
  offsets = [array("Q") for _ in GROUPS]
  uses = [array("I") for _ in GROUPS]

  @lru_cache(maxsize=65536)
  def shortened(name):
    code = identifier(name)
    if code == 0: return b"S"
    value, digits = code >> 2, bytearray()
    while True:
      value, digit = divmod(value, len(RADIX))
      digits.append(RADIX[digit])
      if not value: break
    return GROUPS[code & 3].lower() + bytes(reversed(digits))

  def rename(match):
    name = match.group("ref")
    return match.group(0) if name is None else shortened(name)

  def ensure(code):
    group, index = code & 3, code >> 2
    missing = index + 1 - len(offsets[group])
    if missing > 0:
      offsets[group].extend([MISSING] * missing)
      uses[group].extend([0] * missing)
    return group, index

  offset, before = 0, 0
  with source.open("rb") as input_file:
    for line in input_file:
      head, body = line.rstrip(b"\r\n").split(b" = ", 1)
      if not body.endswith(b";"): raise ValueError("one complete production per line is required")
      group, index = ensure(identifier(head))
      if offsets[group][index] != MISSING: raise ValueError("duplicate production")
      offsets[group][index] = offset
      for ref in references(body[:-1]):
        a, b = ensure(ref)
        uses[a][b] += 1
      offset += len(line)
      before += 1
    # Gaps in generated identifiers are permitted, but referenced gaps are not.
    if not offsets[0] or any(location == MISSING and count
        for group in range(4) for location, count in zip(offsets[group], uses[group])):
      raise ValueError("undefined production")
    data = mmap.mmap(input_file.fileno(), 0, access=mmap.ACCESS_READ)
    try:
      @lru_cache(maxsize=8192)
      def body(code):
        begin = offsets[code & 3][code >> 2]
        end = data.find(b"\n", begin)
        if end < 0: end = len(data)
        return data[data.find(b" = ", begin) + 3:end].rstrip(b"\r;")

      def rewritten(code):
        original = body(code)
        match = CHOICE.fullmatch(original)
        if match is None: return original
        children = tuple(identifier(name) for name in match.groups())
        if any(uses[child & 3][child >> 2] != 1 for child in children): return original
        pairs = [PAIR.fullmatch(body(child)) for child in children]
        if any(pair is None for pair in pairs): return original
        branches = []
        for pair in pairs:
          guard, value = pair.groups()
          guard_code = identifier(guard)
          guard_body = body(guard_code)
          if uses[guard_code & 3][guard_code >> 2] == 1 and NEGATIVE.fullmatch(guard_body):
            guard = guard_body
          branches.append(guard + b" " + value)
        return b" / ".join(branches)

      def expanded(code):
        if not inline_private: return rewritten(code)
        # Grouping preserves precedence below predicates and ordered
        # choices. Limit only substitution depth: deeper private rules remain
        # ordinary references and are reached by the closure below.
        output = bytearray()
        current = rewritten(code)
        frames = [(current, iter(TOKENS.finditer(current)), 0, 0, False)]
        while frames:
          current, tokens, cursor, depth, closing = frames.pop()
          for match in tokens:
            name = match.group("ref")
            ref = identifier(name) if name is not None else None
            if ref is None or ref & 3 != 3 or uses[3][ref >> 2] != 1 or depth >= 16:
              continue
            output.extend(current[cursor:match.start()])
            child = rewritten(ref)
            grouped = needs_group(child, current[:match.start()], current[match.end():])
            if grouped: output.extend(b"(")
            frames.append((current, tokens, match.end(), depth, closing))
            frames.append((child, iter(TOKENS.finditer(child)), 0, depth + 1, grouped))
            break
          else:
            output.extend(current[cursor:])
            if closing: output.extend(b")")
        return bytes(output)

      live = [bytearray(len(group)) for group in offsets]
      work = [0]
      while work:
        code = work.pop()
        group, index = code & 3, code >> 2
        if live[group][index]: continue
        live[group][index] = 1
        work.extend(references(expanded(code)))
      after, fused = 0, 0
      input_file.seek(0)
      with target.open("wb") as output:
        for line in input_file:
          head = line.split(b" = ", 1)[0]
          code = identifier(head)
          if not live[code & 3][code >> 2]: continue
          result = expanded(code)
          fused += result != body(code)
          if short_names:
            head, result = shortened(head), TOKENS.sub(rename, result)
          output.write(head + b" = " + result + b";\n")
          after += 1
      body.cache_clear()
    finally:
      data.close()
  return dict(before_rules=before, after_rules=after, fused=fused,
              before_bytes=source.stat().st_size, after_bytes=target.stat().st_size,
              inline_private=inline_private)


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("source", type=Path)
  parser.add_argument("target", type=Path)
  parser.add_argument("--short-names", action="store_true")
  parser.add_argument("--inline-private", action="store_true")
  args = parser.parse_args()
  print(json.dumps(compact(args.source, args.target, short_names=args.short_names,
                           inline_private=args.inline_private)), flush=True)


if __name__ == "__main__":
  main()
