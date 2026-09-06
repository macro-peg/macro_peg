"""Match a large, concrete PEG file without parsing every unused rule first.

The file contains one complete ordinary PEG production per line, as emitted by
our converters. Only loading is lazy: rule bodies are parsed from those bytes
and executed by the same PEG evaluator. No machine simulator or PAL predicate
is involved in matching.
"""
import mmap
import re

from phase_peg import Grammar


class RuleMap(dict):
  def __init__(self, data, offsets):
    super().__init__()
    self.data, self.offsets = data, offsets

  def __missing__(self, name):
    start = self.offsets[name]
    end = self.data.find(b"\n", start)
    if end < 0: end = len(self.data)
    parsed = Grammar(self.data[start:end].decode("utf-8"), start=name)
    if len(parsed.rules) != 1:
      raise ValueError("one complete PEG production per line required")
    result = parsed.rules[name]
    self[name] = result
    return result


class FileGrammar(Grammar):
  def __init__(self, path, start="S"):
    self.start = start
    self.file = open(path, "rb")
    self.data = None
    try:
      self.data = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_READ)
      offsets = {}
      head = re.compile(rb"([A-Za-z_][A-Za-z_0-9]*)\s*=")
      while self.data.tell() < len(self.data):
        position = self.data.tell()
        line = self.data.readline().lstrip()
        if not line: continue
        found = head.match(line)
        if found is None:
          raise ValueError("one complete PEG production per line required")
        name = found[1].decode("ascii")
        if name in offsets: raise ValueError("duplicate rule")
        offsets[name] = position
      if start not in offsets: raise ValueError(f"unknown start rule: {start}")
      self.rule_count = len(offsets)
      self.rules = RuleMap(self.data, offsets)
    except BaseException:
      self.close()
      raise

  def close(self):
    if self.data is not None: self.data.close()
    self.file.close()

  def __enter__(self):
    return self

  def __exit__(self, *exc):
    self.close()
