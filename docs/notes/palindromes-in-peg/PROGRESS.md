# Towards an explicit plain PEG for PAL — progress log

Goal: an explicit plain PEG for `PAL = { w in {a,b}* | w = reverse(w) }`. Known to exist
(Galil 1978 real-time TM + Kim–Park TM→SCA + LMR `L ∈ PEG ⟺ reverse(L) ∈ SCA`), never written.

## Route (decided 2026-09-04)

1. Write Galil's algorithm faithfully as a multitape Turing machine simulator (Python).
   FPP (Fischer–Paterson, off-line linear-time initial palindromes) is replaced by KMP on
   two tapes, which is trivial on a TM. Check correctness exhaustively and real-time-ness
   (bounded steps per input symbol) mechanically.
2. Port Kim–Park's TM→SCA compiler (`Common/Compiler/RealTimeTM/ToSCA.lean`: tapes as
   zippers = two stacks, so every head move is one hop to an older node).
3. SCA→PEG using the dictionary in `../palindromes-in-peg.md` (position ↔ node, rule value ↔
   forward pointer, one memo column per position ↔ one node per symbol).
4. Verify the generated grammar with macro_peg's interpreter on all strings up to length ~12.

## Why not a pointer-machine design directly

Pointers in a scaffold only reach older nodes. Replacing FPP by KMP over the reversed
pattern needs "the pattern character at state+1", i.e. a newer state node, whichever order
the state nodes are created in (increasing order breaks the successor lookup, decreasing
order breaks the failure computation). A TM head moves both ways, so this is not an issue
there; the TM→SCA compiler handles it once, generically. Also: position comparison
(`LE[m] <= s`) and mirror arithmetic are not pointer-machine primitives; Galil avoids them
by re-running an off-line linear procedure on the window and paying with the predictability
condition (cost O(k) when the tentative centre moves ≥ k/4).

## Stage 1 (done): `stage1_kmp_online.py`

Online LPS tracking where, at a mismatch, the chain of palindromic suffixes of the active
window [s,k] is the KMP border chain of the window (borders of a palindrome are
palindromes; a palindromic window can be scanned forwards by walking backwards).
Correct on all 32,766 binary strings up to length 14. Work per character is O(1)
amortized on most inputs but O(n) on periodic ones ((ab)^200: 100 ops/char): recomputing
the border structure at every mismatch is exactly what Galil's chain case avoids.

## Earlier core: `online_manacher.py`

Position-based online Manacher with the invariant that finalized centres are recorded in
strictly increasing order (so the mismatch scan is a backward walk along the record chain).
Correct on all strings up to length 14. Blocked by position comparison / mirror arithmetic.

## Galil 1978, digested (from the paper)

Heads C (tentative centre), L, R (match outwards), D, search heads. Mismatch with k = R−C:
chain case (C′−C < k/4): a "double palindrome" search in doubling stages runs in parallel
with matching and discovers the chain (Slisenko); the next centre is the next chain node.
Nonchain case: FPP on [L,R]^R gives the longest initial palindrome; C moves ≥ k/4, paying
the O(k) cost via the predictability condition (dt > c ⇒ next output 0, dk ≥ dt/c − 2).
Procedures: main(C,r), dp(C,r), right-dp, extend-the-chain (cases 1 / 2a / 2b), move
(uses main1, the off-line replay of main until R reaches the marked RR).
