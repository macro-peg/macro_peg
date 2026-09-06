---
layout: page
title: Palindromes and PEG — what is explicit, and what is not
---

# Palindromes and PEG: what is explicit, and what is not

`PAL = { w in {a,b}* | w = reverse(w) }`.

## Status

`PAL` **is** in PEG. The chain is: Galil (1978) recognizes all initial palindromes on a real-time
multitape Turing machine; Kim & Park (2026, arXiv:2608.29592) compile a real-time multitape TM into
a *scaffolding automaton* (SCA), machine-checked in Lean; Loff–Moreira–Reis characterize PEG by
`L in PEG` iff `reverse(L) in SCA`. Since `reverse(PAL) = PAL`, `PAL` is in PEG, and LMR's
Conjecture 7 ("even-length palindromes are not in PEG") is false.

The existence argument alone provides no runnable grammar. This repository
now emits a complete ordinary-PEG candidate, described in
[`PLAIN_PAL_ARTIFACT.md`](palindromes-in-peg/PLAIN_PAL_ARTIFACT.md), and tests it
on unchanged input. The earlier examples and failed approaches below remain
useful context; see also
[`PalindromePegs`](https://github.com/macro-peg/macro_peg/blob/main/src/main/scala/com/github/kmizu/macro_peg/examples/PalindromePegs.scala)
and its exhaustive spec.

The current construction work is summarized in
[`HANDOFF.md`](palindromes-in-peg/HANDOFF.md). A complete source on unchanged
binary input has been emitted as an ordinary PEG: 59,169,304 rules /
2,118,673,778 bytes. Ordinary nonterminal inlining reduces it to 13,248,052
rules / 672,208,000 bytes. Actual matching on unchanged input passed all 79
cases: every binary word through length five, selected words through length
33, and four nonbinary inputs. These finite checks are separate from a
formal proof for every input length.
[`WINDOW_ROUNDS.md`](palindromes-in-peg/WINDOW_ROUNDS.md) describes the tested
counter/head/flag components and ordinary-PEG rule compression. Component
results and the earlier repeated-input tests do not establish full raw `PAL`.

## What a plain PEG can compute

With packrat memoization a PEG assigns to each (rule, position) exactly one value: failure, or a
position no earlier than the current one. So a plain PEG *is* a right-to-left dynamic program that
keeps a constant number of **forward pointers** per input position — which is precisely an SCA
reading the reversed input. The dictionary behind LMR's theorem:

| plain PEG (input `w`, memo table) | SCA (input `reverse(w)`) |
|---|---|
| input position `i` | node created at step `n - i` |
| value of rule `A` at `i` (a position `>= i`) | an edge of that node, pointing to an older node |
| which rules succeed at `i` | the node's label (finite) |
| a rule body = O(1) calls and character tests | at most `radius` pointer hops from the previous top |
| one memo column per position | one node per input symbol (real time) |

Two consequences worth remembering:

* A PEG rule value is a function of **one** position. Anything that needs a (start, end) pair — the
  palindromic-prefix chain, an eertree node's occurrence — has no direct encoding.
* A PEG can chase pointers arbitrarily far (`A = [ab] A / ...` walks to the end) and can find the
  *farthest* position satisfying a local condition, but it can never advance "by the length of
  something measured elsewhere". No length transfer.

The equivalence supplies a machine interpretation of any plain PEG; it does not require that
the grammar encode Galil's particular algorithm, nor establish a lower bound on grammar size.
The failed candidates below refute those candidates and the tested search spaces only. They do
not rule out a compact direct grammar, a different SCA construction, or a repaired recursive
scheme with a different invariant.

## What fails, measured

1. The textbook grammar `P = "a" P "a" / "b" P "b" / [ab] / "";` rejects `"aaaa"`: ordered choice
   commits to one split of the middle. (Formalized in `lean4-peg`, `Shallot/Peg/Palindrome*.lean`.)
2. **Mirror re-anchoring does not hold.** For the chain of palindromic prefixes at position `j`,
   take the longest (`[j, e1)`) and the second longest (`[j, e2)`), and mirror the second inside the
   first, at `m = j + e1 - e2`. The hope is `LPP(m) = e1`, which would make the chain walk a
   per-position rule. Over all binary strings of length `<= 14` it holds for only 229,892 of 425,986
   instances; the smallest counterexample is `w = "ab"`, `j = 0` (there `LPP(m) = 2`, not `1`).
3. **Exhaustive screen of naive shapes.** 64,460 grammars built from
   `{"a" A "a", "b" A "b", "a", "b", "", a-run, b-run, "aa", "bb"}` (checked on all 1,023 strings of
   length `<= 9`) and 64,350 grammars over a richer pool with run/tail gadgets (all 4,095 strings of
   length `<= 11`). None came within 3, resp. 2, mistakes of `PAL`.
4. **A deep randomised search finds nothing either.** 400,000 random two-rule grammars
   over a rich atom set (rule calls, `&`/`!` predicates, runs, right-anchored gadgets,
   mirrored pairs) were screened on 26 discriminating strings — `"aaaa"`, `"ababa"`,
   `"aabaabaa"` among them. **Not one passed the screen**, let alone the full check.
5. **The best shape found is sound but incomplete.**
   `A = "a" A "a" / "b" A "b" / [a]+ &("b" / !.) / [b]+ &("a" / !.) / "";` never accepts a
   non-palindrome (checked to length 13) yet misses 104 of the 381 palindromes of length `<= 13`.
   The misses are exactly the periodic ones — `"ababa"`, `"aabaabaa"`, `"abbabba"` — where several
   palindromic decompositions compete and the ordered choice takes the wrong one.

## What is explicit

**Ordinary PEG midpoint component, arbitrary lengths.**
[`midpoint.peg`](palindromes-in-peg/generated/midpoint.peg) has fixed rules
`HalfFloor` and `HalfCeil` returning the cuts after `floor(n/2)` and `ceil(n/2)`
characters of the current binary suffix. Its 269,633-byte construction and
invariant are described in [MIDPOINT.md](palindromes-in-peg/MIDPOINT.md).
It does not yet compare the two halves or recognize PAL.

All of the following are generated by `PalindromePegs` and verified exhaustively against a
reference predicate in `PalindromePegsSpec` (all binary strings up to length 10, and up to length
13 for the macro grammars).

**Macro PEG, full `PAL`** — two rules:

```
S = P("") !.;
P(r) = "a" P("a" r) / "b" P("b" r) / [ab] r / r;
```

The parameter `r` accumulates the reversed prefix; `[ab] r` closes an odd-length palindrome and `r`
an even-length one. The order is not cosmetic: swapping the last two alternatives makes the grammar
reject `"aaa"`.

This grammar has been checked against the palindrome predicate on **all 131,071 binary strings up
to length 16**, and on 400 random strings of length 17-60 (half of them palindromes), with no
mismatch — so PAL has an explicit, executable, exhaustively verified grammar in Macro PEG. What
remains unfinished in this repository is an explicit *plain* PEG. The Galil + Kim-Park + LMR
route gives an existence argument, but does not prescribe a compact construction.
`notes/palindromes-in-peg/PROGRESS.md` records the attempted machine translation and its
obstacles; its size is not evidence that PAL intrinsically needs a large PEG.

**Macro PEG, even-length `PAL`** — drop `[ab] r`. That is LMR's Conjecture 7 language, in two rules.

**Plain PEG, inner family** `L_K = PAL and (at most K occurrences of 'b')`, size O(K):

```
S = A2 !. / A1 !. / A0 !.;
A2 = "a" A2 "a" / "b" A0 "b";
A1 = "a" A1 "a" / "b";
A0 = [a]*;
```

`L_0 subset L_1 subset ...` and the union is `PAL`: the `'b'`s act as synchronizers, so the a-runs
pair up deterministically.

**Plain PEG, outer family** `D_K = { w | w(k) = w(|w|-1-k) for every k < K with 2k+1 < |w| }`,
size O(K²):

```
S = &C0 &C1 [ab]* !.;
C0 = !([ab] [ab]) / ( "a" &(A0) / "b" &(B0) );
C1 = !([ab] [ab] [ab] [ab]) / [ab] ( "a" &(A1) / "b" &(B1) );
A0 = [ab] A0 / "a" !.;
B0 = [ab] B0 / "b" !.;
A1 = [ab] A1 / "a" [ab] !.;
B1 = [ab] B1 / "b" [ab] !.;
```

`D_0 superset D_1 superset ...` and the intersection is `PAL`. The gadget is **right-anchored
addressing**: `A = [ab] A / "c" [ab]^k !.;` succeeds exactly when the character sitting `k`
positions from the right end is `c`, in O(k) rules and with no counting — the recursion descends to
the end of the input first and unwinds onto the unique position with `k` characters after it.

**Plain PEG, bounded slices** `PAL and (length <= N)`, exactly, size O(N²) — `bounded(40)` is 61
rules / 1,535 tokens, while any DFA for the same language needs at least 2^20 states (distinct
20-character prefixes must be distinguished).

So `PAL` is squeezed between two explicit chains of plain PEG languages, and every bounded-length
slice of it is an explicit plain PEG. The macro parameter is exactly what buys the unbounded limit
from a fixed number of rules — and, per LMR, a plain PEG can buy it too, but only by encoding a
real-time detector.

## Work in progress: towards the explicit grammar

`palindromes-in-peg/online_manacher.py` is a verified algorithmic core for the real route: an
online Manacher over positions whose finalized centres are recorded in strictly increasing
order, so the scan at a mismatch is a backward walk along the record chain — one pointer hop per
step, which a scaffold (hence a PEG memo table) can do. What remains is realizing the comparison
`LE[mc] <= s` and the reflections `C - mc + LE[mc]`, `k + 2s - mc` about the active centre with
delayed lockstep walks (Galil's tape heads), then compiling the state machine to rules.

## References

* Z. Galil, *Palindrome recognition in real time by a multitape Turing machine*, JCSS 1978.
* B. Loff, N. Moreira, R. Reis, *The computational power of parsing expression grammars*.
* J. Kim, S. Park, arXiv:2608.29592 (2026); Lean artifact, Zenodo 22099762.
* `lean4-peg`, `docs/notes/palindromes-in-peg/` on branch `docs/t7-resolved-and-palindromes`.
