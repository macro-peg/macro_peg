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

### Stage 4 structures (done): `scavm_structs.py`

Stacks (cells addressed as (node, creator, slot), several cells per step via slots),
unary counters (two stacks), and a Hood–Melville real-time queue (rotation with
immutable chains: the walkers read the old front without destroying it, so live pops
during rotation are just a second pointer into the same chain; 3 units per step).
Checked against `collections.deque` on random (3,000 ops) and adversarial (2,100 ops)
sequences; radius 29, 43 fields, label count plateaus (422/593/674 at 3k/10k/30k steps).
This closes the two "move right" rows of the plan: marks passed by R are queued.

### How the port maps

Every `yield` in `stage3_tm_galil_chain.py` is one unit of O(1) reads, so the
generator maps one-to-one onto machine steps with a bounded number of units each;
the real-time driver (budget per symbol, output 0 while lagging) *is* the machine's
control: a pointer to the simulated-time node lags behind the top, input nodes are
the buffer.  The rewrite replaces every integer position or KMP state by a pointer
to a (node, slot), every array by slot cells, every comparison by pointer equality
or a lockstep walk, and every generator local by a field of the top node.

### Stage 5, block 1 (done): `stage5_port_block1.py`

`match` + nonchain `move` as a scaffolding-automaton program with the lag built in:
the input nodes go through a real-time queue Q (the simulation pops the next symbol,
so "advancing simulated time" needs no pointer to a newer node); KMP over the
palindromic window uses cells (node, slot) with `fail` and `wn` (window node); the
cursor is a zipper Lz / Rz / A (left stack, right stack of cells returned by failure
jumps, append queue) which replaces the two-head pattern tape a Turing machine would
use (Chuang–Goldberg: multihead TM ⇔ real-time deque; here Rz ++ A suffices because
every position in Rz precedes every position in A).  Online (unbounded units per
step): correct on all strings up to length 12.  Bugs found on the way: queue work
must run per operation, not per step; SELF (this step's node) fields must be readable;
the append queue must be cleared when a new KMP starts.
Hop bound: with one unit per step, the maximum hops per step is 70–76 on inputs of
length 100–600 (constant), 127 pointer fields per node; failure jumps advance one
cell per unit.  So block 1 is a genuine scaffolding-automaton program.

### Block 2 design: the chain machinery without moving right

Three operations of stage 3 move right over existing nodes; each becomes a leftward
walk through the palindrome's mirror symmetry:

1. **Head D (periodicity watch).** Galil's D oscillates over one semiperiod next to
   C.  Read the palindrome [C−2h_G, C] (its start is known from the dp search)
   *leftwards* from C, wrapping back to C at its start: by symmetry this is the
   periodic sequence a_C, a_{C+1}, … that predicts each new a_R.  The phase depends
   only on R, so the walker survives chain-case moves of C.
2. **L after a chain-case move (L + 2h_G − 2).** Keep L virtual: the extension test
   needs a_{L−2}, whose mirror about the old centre is a_{R − lag} with a *constant*
   lag = k(2h_G−2) − 2 after k chain moves — a delay line (real-time queue) fed by
   R, lengthened by h_G−1 nodes at each chain move, and a unary counter for the
   virtual offset k(2h_G−2) − 2t that returns L to the real pointer L_old when it
   reaches 0.
3. **Mirror computation** when a real place is needed (KMP window at a nonchain
   mismatch; the new centre for a coarser-chain search): mirror(p) = R_old − (p −
   L_old), obtained by walking p→L_old and R_old→· leftwards in lockstep, cost
   O(|W_old|), which the nonchain case pays; for the coarser-chain search it can
   run as the first phase of the paced background search.

### Block 2 design check (done): `stage3b_design_check.py`

Stage 3 with the two replacements asserted against the originals at every step:
the cyclic head Dc over [C−h, C] (step −2, wrap +h) agrees with D, including across
chain-case moves (D ≡ L−2 mod h is preserved), and the mirror read a_{R−lag} with
lag = k·h − 2 after k chain moves equals a_{L−2} whenever L is virtual (offset
k(h−2) − 2t > 0).  All strings up to length 12 online and at real-time budget 512,
periodic stress inputs: correct, no assertion fired.

## Correction (2026-09-04): SCAs cannot compare node identities — pivot to a legal TM

Re-reading `Common/Model/Scaffolding.lean`: the transition receives
`Neighborhood radius`, a **tree of labels** (no sharing information), and an action
names its new pointers by `LocalTarget` = a **path of directions**.  So a scaffolding
automaton can never ask "are these two pointers the same node".  The only landmark is
the absent pointer (start of input).

That invalidates the pointer-machine port as written: `stage5_port_block1.py` and
`scavm_pal.py` use identity in 9 places (`wj is L`, `simR is L`, cell `.same(...)`,
place `.same(...)`).  The VM did not catch it because Python identity is invisible to
the model.

How Galil avoids it: **he writes marks on the tape** — "the r symbols left of C are
marked", "mark semiperiods (places C−k, …)", "Mark R as RR", "the Bth place is
marked".  A Turing head detects a landmark by *reading a marked symbol*, never by
comparing positions.  And Kim–Park's TM→SCA compiler represents each tape as a zipper
(two stacks), so a head move is one hop to an older node and a write is a new node
version: no identity needed anywhere.

**Consequence — the route changes.** Hand-porting to pointers was the wrong level of
abstraction.  The correct one:

1. write Galil's algorithm as a *legal* multitape TM: finite control, heads that only
   read the scanned symbol, write it, and move ±1 — no integer places, no random
   access (stage 3 uses `m.rd(place)`, so it is a specification, not a machine);
2. compile TM → SCA mechanically (zippers, as in `ToSCA.lean`);
3. compile SCA → PEG mechanically (the dictionary in `../palindromes-in-peg.md`).

Steps 2 and 3 are generic and mechanical, and step 3 is what finally yields the
grammar.  The pointer-level work is not wasted: the real-time queue, the KMP zipper
and the mirror/cyclic-D design checks all carry over as the *implementation* of the
tapes, and the stage-3b design check shows the chain machinery needs no rightward
scan.  But the identity-free discipline has to come from marks on tapes.

## The compiler works: `tm2peg.py` (real-time multitape TM -> plain PEG)

The pipeline that will produce the palindrome grammar now exists and is verified.

Encoding.  PEG position `i` holds the machine's configuration after it has read
`reverse(w)` up to `w(i)`, so a rule at `i` is computed from rules at `i+1` and the
character `w(i)`, and position `n` (recognisable by `!.`) carries the initial
configuration; the machine accepts iff the state at position 0 is accepting, i.e.
`S = (&(St_qf) / ...) [ab]* !.;`.  Each tape is a zipper and its two stacks are
chains of memo positions:

    unchanged    Lt_j <- . Lt_j              the value at position i+1
    push here    Lt_j <- ""                  this position
    pop          Lt_j <- . Lt_j . Lt_j       the cell below the top

(the cell below the top of the stack at position `p` is the top of the stack at
`p+1`), and the top cell's symbol is read as `. Lt_j Lsym_j_s`.  Rules: `St_q`
(state), `Sc_j_s` (focus symbol), `Lt_j` / `Rt_j` (stack tops), `Lsym_j_s` /
`Rsym_j_s` (symbol pushed here) — all predicates or forward jumps, so no position or
identity comparison anywhere.

Verified end to end:

* a regular machine (even number of `a`s): 8 rules, 132 tokens, exact on all strings
  up to length 9;
* a real-time counter machine for `a^n b^n` (the counter is the head position, with
  markers `$ 1 2` at the bottom cells so the focus tells the machine when the counter
  is small): **30 rules, 6,006 tokens, exact on all 2,046 binary strings up to length
  10 and on a^30 b^30 / a^30 b^29**.  The grammar is checked into
  `generated/anbn_from_tm.peg` and re-verified by macro_peg's own interpreter in
  `GeneratedFromTmSpec`.

So: write Galil's algorithm as a legal real-time multitape TM and this compiler emits
an explicit plain PEG for PAL.  That machine is the only remaining piece.

### A palindrome language, compiled from a machine

`generated/marked_palindrome_from_tm.peg` (23 rules, 3,923 tokens) is a plain PEG for
`{ u # reverse(u) }`, compiled from a real-time one-tape machine: push `u`, turn
around at `#`, then pop-and-compare, with the bottom cell of the stack carrying a
marked symbol (`A`/`B`) so the machine knows within the step that the stack has just
become empty (acceptance is by state alone).  Exact on all 2,187 strings over
`{a,b,#}` up to length 7.  The point is not the language — a three-alternative PEG
does it — but that the *pipeline* produces a palindrome grammar from a machine.

## What is still missing for full PAL, precisely

A PEG for PAL needs a machine that decides, within each step, whether the prefix read
so far is a palindrome (the machine cannot know which step is the last).  Worst-case
O(1) per step is not strictly necessary: an amortized-O(1) online algorithm suffices
if it satisfies Galil's predictability condition, because the lag mechanism then never
suppresses a `1` — which stage 3 confirmed empirically at budget 512.  So the target
is any online palindromic-prefix algorithm implementable in this model.  Three
candidates, each with an obstacle we have now located exactly:

| algorithm | obstacle in the SCA/PEG model |
|---|---|
| eertree (suffix links) | needs `add[c][u]`, a child pointer written into an *existing* node; nodes are immutable, and the parent-to-child direction is the one pointers cannot take |
| Manacher | needs `min(r[mirror], R − i)`, i.e. a comparison of two stored positions; the model has no pointer comparison, and a race costs O(radius) per step |
| KMP over the window with pointer failure links | the cursor must move to the *successor* cell (state + 1); cells are created in increasing order, so the successor is always a newer node.  A zipper fixes that, but the failure jump then has to pop "until the top is the target", which is a comparison again; persistent snapshots remove the comparison but lose the cells created after the snapshot |
| Galil 1978 as written | no model obstacle — but it uses FPP (Fischer–Paterson linear-time string matching on a Turing machine) as a subroutine, which is itself a substantial machine to build |

So the honest state: the compiler is done and verified, and the remaining work is one
of these machines — Galil's being the only one with no model obstacle, at the price of
implementing FPP.  That is days of work, not hours.

## FPP: the reference, and why the KMP route is blocked (`fpp.py`)

`fpp.py` computes all palindromic prefixes of a string as the borders of `u # reverse(u)`
(a border of length L says `u[0..L-1] = reverse(u[0..L-1])`), verified against brute force
on all binary strings up to length 12.  Cursor cost measured over 200 random / periodic /
palindromic strings: 1.17 cursor moves per character, i.e. genuinely linear.

Porting it to the machine model fails at a precise point.  The KMP cursor needs, at state
`k`: the symbol `P[k+1]`, a move to `k+1` on a match, and a jump to `fail[k]` on a
mismatch.  With pointer-carrying cells,

* a **left-to-right** pass can store `fail` (its target `fail[k] < k` already exists) but
  not `next` (the target does not exist yet);
* a **right-to-left** pass can store `next` but not `fail`;
* the cursor needs both **on the same cell**, and splitting into two chains only moves the
  problem: keeping a cursor into both chains in step requires advancing in the fail chain,
  which is the same wall;
* a zipper gives `+1` without pointers, but then the jump must pop "until the top is the
  target" — a pointer comparison, which the model does not have; persistent snapshots
  remove the comparison but lose every cell created after the snapshot.

In other words KMP wants **random access to `fail[]`**, a RAM operation.  That is exactly
why Galil cites Fischer & Paterson rather than KMP: their linear-time method is
convolution-based, a different technique altogether.  The realistic route to FPP in this
model is therefore a constant-space string-matching technique — Galil–Seiferas or
Crochemore–Perrin critical factorization, whose state is O(1) positions, i.e. heads —
rather than any failure-function algorithm.  That is the next concrete piece of work.

### The way in: constant-space periods (verified)

`fpp.py` now carries `maximal_suffix` / `critical_factorization` / `period_if_periodic`
(Crochemore–Perrin).  `period_if_periodic(x)` returns the smallest period of `x` when it
is at most `|x|/2`, and `None` otherwise; verified against brute force on **all 32,766
binary strings up to length 14** (624 of them periodic), no mismatch.

Why this matters: it uses four indices and one left-to-right scan and nothing else — no
array, no random access — so it is directly a machine with a few heads.  And the case it
covers, "the period is at most half the length", is exactly Galil's **chain case**, the
one that has to be cheap.  The border chain of a periodic word is the arithmetic
progression `n - t*p`, and what remains below `n/2` is the border chain of a word at most
half as long, so the recursion telescopes to O(n).

What is still missing for a full FPP: when the word is *not* periodic (period > n/2), its
longest border is shorter than `n/2` and the critical factorization does not name it.
Finding that one needs a constant-space *matching* pass (the two-way algorithm's search
phase) rather than its preprocessing phase.  That is the next piece.
