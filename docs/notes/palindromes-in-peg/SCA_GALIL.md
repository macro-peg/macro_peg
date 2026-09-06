# Whole online scaffold controller: implementation and proof obligations

**2026-09-06:** This is a record of the previous experimental controller.
The current plan is [TRANSLATION_STRATEGY.md](TRANSLATION_STRATEGY.md).
Its input-event, predictability and one-node-per-symbol contracts supersede
the old next-output instructions below. The proposed ledger here remains
unverified; it is not a reason to retain or tune the numeric clocks.

`scaffold_galil.py` connects the actual finite FPP and DP instruction tables,
readonly letter/gap heads, periodic prediction, center shifts, and replay.
It is an executable candidate, **not a completed PAL PEG or a certified
real-time recognizer**. `recognize` uses 2,048 scaffold ticks per input letter;
`run` without a budget instead drains work after each arrival.

## Implemented transitions

The five input heads are R, L, C, W (window copying), and V (period verification).
Positions and lengths are represented by persistent stacks, not host integers.
All five heads receive the same arrival. Gap places are finite phases of these
heads, so the input alphabet remains `{a,b}` without a padding character.

During ordinary matching, one place is compared every 256 scaffold ticks.
Each background DP tick runs up to 64 actual finite instructions. Stage setup,
window copying, and doubling still perform bounded local work, one step per
tick. DP discoveries supply the real unary semiperiod to `ChainView`.

The chain copies C through C-h, bounces a private tape between its ends, and
catches V up with R. Four verified semiperiods permit a chain shift. A shift
moves C by h and L by 2h through individual head moves; it builds a unary 2h
countdown for the next continuation. Actual input comparisons check that the
left symbol agrees with the prediction inside that continuation and disagrees
at its endpoint. A period break with matching outer symbols restarts DP using
the previous semiperiod boundary as its lower bound.

On a nonchain mismatch the controller copies the reversed candidate window to
FPP, selects its longest odd palindromic prefix, moves C and L back from R, and
replays matching from C to the saved R position. The replay distance is unary.
The DP and FPP scratch roots are separate and discarded in constant time.

## Static fact checked in the regression suite

For each tape, max-plus analysis over *all* control-flow paths of length 64 in
both instruction tables bounds movement by 32. This includes infeasible branch
combinations and is therefore stronger than a runtime sample. The marked-FPP
return performs two additional moves. Tape cells created during a quantum fit
within the 64 finite slots supported by the scaffold representation.

## Proposed work ledger (not yet a complete proof)

The following deliberately loose ledger is a working derivation. Its charging
arguments must be checked against every instruction-table path before using it
as a theorem or an acceptance guarantee.

For a kernel input of length N, proposed charges are: queue work 26N, S copies
20N, fallback bookkeeping 14N, C moves 10N, associated alias/read work 20N,
matched S pushes 12N, B advances 2N, retries/comparisons 8N, failed A probes 6N,
and chain bookkeeping 2N. This totals 130N. Mark tracking adds at most 19N.
The marked construction uses N=2m+1, and setup plus DP scan then suggest
`331m+223 <= 400(m+1)` instructions for a window of m places.

With a 64-instruction quantum, the first DP stage for lower bound r>0 is
estimated at `69r+24` ticks, starting at radius at most `floor(5r/3)` and with
deadline radius 2r. The r=0 case uses an eight-place span. Later stages have a
larger margin. The 256-tick match clock is intended to satisfy these deadlines
and finish period preparation before the matching radius reaches 4h.

For a nonchain mismatch at radius k, the copied window has m=2k places.
Including replay, the proposed fallback bound is 276.5k ticks. Galil's center
advance bound `delta C > (k-1)/4`, if applicable to every fallback in this
implementation, then permits a charge of 1,400 ticks per place advanced by C.
Chain shifts cost at most twice their center advance. Normal matching costs
512 ticks per input letter (two places).

The proposed catch-up argument considers a busy interval from a fully processed
prefix j to a palindromic prefix n. In letter/gap coordinates, C at j is at
least j, while at n it equals n. Telescoping center advances would bound work
by `(512+1400)(n-j)`, below the available `2048(n-j)` ticks. A report made while
behind is zero; proving the bound is what would exclude missed positives.

Outstanding obligations are the exact finite-kernel ledger, DP entry and
deadline invariants (including every restart), the mapping of every fallback
to Galil's nonchain lemma, and initialization/busy-interval endpoints. These
are not established merely by passing the finite tests.

## Regression scope and next output step

Tests cover all binary words through length four, periodic shifts, period
breaks and replay, a restart witness `ab` + `a` repeated 20 times + `ba`, and
fixed-budget execution. A four-times-faster experimental match clock previously
missed a stage deadline on `babbababaaaabababbaba`; the 256-tick setting passes
that witness. This is evidence for retaining the slower clock, not a proof of
its sufficiency for arbitrary length.

The final conversion still needs an explicit finite symbolic transition
description for this scaffold controller. `symbolic_sca2peg.py` now compiles
Boolean label equations and conditional pointer equations directly to ordinary
PEG. Its separate marked-palindrome example has 35 rules and 607 bytes; five
Python tests and a Scala Interpreter regression pass. It shares expressions
instead of enumerating complete neighborhoods. This does not yet translate
`scaffold_galil.py` into those equations.

Existing `symbolic_tm2peg.py` accepts a different TM transition interface;
passing this Python procedure to it is not a valid conversion. Existing
`phase_peg.py` can remove a fixed microstep expansion once those PEG rules exist.
