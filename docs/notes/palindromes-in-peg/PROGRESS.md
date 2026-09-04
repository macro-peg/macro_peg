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

## Stage 2 (done): `stage2_tm_galil.py`

Tape/head machinery with unit-cost accounting, the online driver and the budgeted
real-time driver (Galil's on-line -> real-time transformation: fixed budget per symbol,
print 0 while lagging).  `match` + nonchain `move` only (FPP = KMP on work tapes).
Correct online on all 8,190 strings up to length 12, but O(n) per symbol on periodic
inputs and the real-time driver fails (predictability needs the chain case).

## Stage 3 (done): `stage3_tm_galil_chain.py`

Galil's places encoding; chain case via the double-palindrome search `dp(C, r)` in
doubling stages (KMP per stage), paced at RATE = 64 units per symbol and replayed
off-line after a `move` (main1); chain confirmation once R >= C + 4h_G, with the
periodicity verified up to R at 4 places per symbol (right-dp), then head D watches
each extension; case 1 (chain ends) restarts the search with r = CH_last - C;
case 2b (chain case) moves the centre by h/2 at cost O(h).

Results: online correct on all 8,190 strings up to length 12; periodic inputs cost
5-10 units per symbol ((ab)^100: 5.7, previously 620).  **Real-time driver with
budget 512 units per symbol: 0 mismatches on all 8,190 strings and on all periodic
stress inputs** — the predictability condition holds empirically for this
implementation.  Further: 0 mismatches on all 24,576 strings of length 13-14 and on
300 random strings of length 50-300 (random / periodic / built from palindromes).  The constant is large because the search replay charges
RATE·(R-C) against a centre move of (R-C)/4; it does not matter for the PEG.

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

## Stage 4 (started): `scavm.py` — the scaffolding-automaton VM

The VM enforces the SCA discipline mechanically: one node per input symbol, immutable,
finite label + bounded pointer fields, pointers only to nodes reached this step via
`get` (one hop each), per-step hop count (radius) and distinct-label count reported.
`demo_extension_only` (Galil's `match` alone) runs at radius 2 with 3 labels.

Port plan for `stage3_tm_galil_chain.py`, component by component:

| stage-3 component | pointer-machine realization |
|---|---|
| input places, heads L, R | input chain with `prev`; R = top; L moves by one `prev` hop; a place = (node, half) |
| `move`: C = midpoint of [L,R] | two backward walkers from R at speeds 2 and 1; when the fast one meets L the slow one is at C. Cost O(R−L), paid (nonchain) |
| KMP over the window | window read backwards = forwards (palindrome). State j ↔ (node, slot): RATE states per step packed into one node's slots; `fail` = pointer to an older (node, slot) |
| dp check "2h+1 and 4h+1 both present" | walk the fail chain and the state chain in lockstep downwards, with two state walkers at speeds 2 and 4 (Galil's two heads on marks); O(window) |
| chain confirmation R ≥ C + 4h_G | pointer equality: L reaches the start node of the 4h_G+1 palindrome (mirror) |
| head D (period watch) | pointer moving left with L; chain case: new L = D |
| **new D = newL + h − 2 (moving right)** | open: needs marks revisited left-to-right ⇒ a real-time queue (Hood–Melville with unary counters as stacks), or a stack of (L,R) mirror pairs pushed during matching and walked down (cost O(R−C), only acceptable if the timing invariant tolerates it) |
| **C after a chain step (C + h_G)** | same issue; alternative: recover C lazily from the (L,R) pair stack when a search through the new C is started |
| replay after `move` (RATE·(R−C) units) | a walker from R back to C paces the replay: RATE units per hop |
| lag / predictability | the delay wrapper becomes part of the machine: a pointer to the simulated-time node, output 0 while behind |

The two open rows are the only places where something must move *right* over
already-created nodes; everything else is backward walks and pointer jumps.
