"""Check a concrete ordinary PEG on unchanged binary words with Rust.

The runner receives words directly, without --repeat or a work protocol.
The complete runner output and a compact verification manifest are retained.
The manifest status is running, passed, or failed; only passed is evidence of
a completed check. Failures retain the checked count and offending report.
"""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import subprocess
import time


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("grammar", type=Path)
  parser.add_argument("--runner", type=Path, required=True)
  parser.add_argument("--max-length", type=int, default=5)
  parser.add_argument("--log", type=Path, required=True)
  args = parser.parse_args()
  if not 0 <= args.max_length <= 12:
    parser.error("the exhaustive test range must be between 0 and 12")
  words = ["".join(chars) for n in range(args.max_length + 1)
           for chars in itertools.product("ab", repeat=n)]
  words += ["abbba", "abbbba", "ababa", "abaaba", "abbaabba", "abbaabab",
            "abbbbbbbbbbba", "abbbbbbbbbbab", "a" * 17, "a" * 33,
            ("a" * 8 + "b") * 2 + "a" * 8, "ab" * 9,
            "abba" * 8, "abba" * 7 + "abab", "#", "a!a", "a.a", "abcba"]
  words = list(dict.fromkeys(words))
  verify(args.grammar, args.runner, words, args.log, args.max_length)


def verify(grammar, runner, words, log_path, max_length):
  manifest_path = log_path.with_suffix(".json")
  paths = [path.resolve() for path in (grammar, runner, log_path, manifest_path)]
  if len(set(paths)) != len(paths):
    raise ValueError("grammar, runner, log and manifest must use distinct paths")
  started, checked, process = time.monotonic(), 0, None
  manifest = dict(status="running", grammar=str(grammar), checked=0, total=len(words),
                  exhaustive_max_length=max_length, input_transform="none", log=str(log_path))

  def save():
    manifest.update(checked=checked, seconds=time.monotonic() - started)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

  # Invalidate an earlier success before hashing, launching, or parsing output.
  save()
  try:
    with log_path.open("w", encoding="utf-8") as log:
      with grammar.open("rb") as source:
        manifest.update(sha256=hashlib.file_digest(source, "sha256").hexdigest(),
                        bytes=grammar.stat().st_size)
      save()
      command = [str(runner), str(grammar), *words]
      process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
      for line in process.stdout:
        log.write(line)
        log.flush()
        if line.startswith("loaded\t"):
          print(line.rstrip(), flush=True)
        if not line.startswith("match\t"): continue
        manifest["last_report"] = line.rstrip()
        if checked >= len(words):
          raise ValueError("runner reported more matches than requested")
        fields = line.rstrip().split("\t")
        word = words[checked]
        manifest["current_word"] = word
        actual = fields[2] == "true"
        expected = all(char in "ab" for char in word) and word == word[::-1]
        details = dict(field.split("=", 1) for field in fields[3:])
        if fields[2] not in ("true", "false") or json.loads(fields[1]) != word or actual != expected or \
            int(details["characters"]) != len(word) or details["repeat"] != "1":
          raise AssertionError(dict(word=word, expected=expected, report=line.rstrip()))
        checked += 1
        if checked % 16 == 0 or checked == len(words):
          print(f"checked {checked}/{len(words)} unchanged inputs", flush=True)
    if process.wait() or checked != len(words):
      raise RuntimeError(f"runner exited with {process.returncode}; checked {checked}/{len(words)}")
    manifest["status"] = "passed"
    manifest.pop("current_word", None)
    manifest.pop("last_report", None)
  except BaseException as error:
    manifest.update(status="failed", error=f"{type(error).__name__}: {error}")
    raise
  finally:
    try:
      if process is not None:
        if process.poll() is None:
          process.terminate()
          try:
            process.wait(timeout=5)
          except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        manifest["returncode"] = process.returncode
        process.stdout.close()
    finally:
      save()
  print(json.dumps(manifest), flush=True)
  return manifest


if __name__ == "__main__":
  main()
