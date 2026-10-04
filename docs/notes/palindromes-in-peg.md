---
layout: page
title: Palindromes and PEG — moved to lean4-peg
---

# Palindromes and PEG

`PAL = { w in {a,b}* | w = reverse(w) }` is in PEG (Galil 1978 + Kim–Park 2026 + LMR), and
an explicit, executable **plain** PEG for it now exists: 13,248,052 rules / 672 MB, generated
from a real-time online palindrome recogniser via a scaffolding automaton, verified on unchanged
input up to length 2,048 and structurally checked to be closed and genuinely recursive.

The construction, the generators that reproduce the grammar byte for byte (SHA-256
`ab891da2…`), the Rust runner, the verification logs and the notes on every failed route now
live in **[kmizu/lean4-peg](https://github.com/kmizu/lean4-peg)**, next to the PEG semantics
and the palindrome counterexamples formalised there:

* [`docs/palindromes-in-peg.md`](https://github.com/kmizu/lean4-peg/blob/main/docs/palindromes-in-peg.md) — the PEG/SCA note
* [`docs/palindromes-in-peg/PLAIN_PAL_ARTIFACT.md`](https://github.com/kmizu/lean4-peg/blob/main/docs/palindromes-in-peg/PLAIN_PAL_ARTIFACT.md) — the artifact, reproduction and verification

What stays in this repository: the Macro PEG for `PAL` (two rules), the plain-PEG inner/outer/bounded
families, all in
[`examples/PalindromePegs.scala`](../../src/main/scala/com/github/kmizu/macro_peg/examples/PalindromePegs.scala)
with exhaustive specs, and the small machine-generated grammars used as test fixtures under
`src/test/resources/palindromes/`.

Macro PEG, full `PAL`:

```
S = P("") !.;
P(r) = "a" P("a" r) / "b" P("b" r) / [ab] r / r;
```
