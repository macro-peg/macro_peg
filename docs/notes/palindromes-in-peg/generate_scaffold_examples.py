"""Regenerate ordinary PEG examples from symbolic scaffold equations."""
from pathlib import Path

from symbolic_sca2peg import (Scaffold, TRUE, FALSE, SELF, NULL, symbol, old,
                              exists, negate, both, either, pointer, select)


def marked_palindrome():
  right = old((), "right")
  match = either(both(symbol("a"), old(("top",), "a")),
                 both(symbol("b"), old(("top",), "b")))
  live = both(old((), "live"), either(negate(right), match))
  return Scaffold(
    {"a": False, "b": False, "right": False, "live": True, "out": False},
    {"a": symbol("a"), "b": symbol("b"),
     "right": either(right, symbol("#")), "live": live,
     "out": both(live, either(
       both(negate(right), symbol("#"), negate(exists(("top",)))),
       both(right, match, negate(exists(("top", "next"))))))},
    {"top": select(right, pointer(("top", "next")),
                   select(symbol("#"), pointer(("top",)), SELF)),
     "next": pointer(("top",))}, "out", "ab#")



if __name__ == "__main__":
  output = marked_palindrome().compile()
  (Path(__file__).parent / "generated" / "scaffold_marked_palindrome.peg").write_text(output)
  print(f"{len(output.splitlines())} rules, {len(output.encode())} bytes")
