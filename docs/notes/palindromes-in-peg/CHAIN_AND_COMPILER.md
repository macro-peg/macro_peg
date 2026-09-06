# Chain monitoring and the sparse PEG compiler

These are composition components toward PAL, not a complete PAL recognizer.
`FPP_CALLING_CONVENTION.md` describes the underlying offline search.

## Search followed by right-dp and triple matching

`chain_finite.build_chain_program("abs")` has 4,510 states and eighteen
tapes. It starts with the same center-tagged WINDOW and unary LOWER as the
local DP search. All remaining tapes start blank at zero. The added tapes
are PERIOD (16) and a one-cell input PORT (17).

On a DP miss, it halts with `no_chain`. On success it copies the first
marked semiperiod C..C-h to PERIOD, retaining the letters and marking its
first/last data cells. This costs O(h) local moves. It returns WINDOW to C
and begins matching WINDOW leftward against right-side places supplied
through PORT, starting at C+1. The PERIOD head bounces between its endpoint
marks; it never performs a whole-period rewind between incoming places.

The caller writes one place to PORT only at a `ready` state. The controller
consumes it, clearing the port, before reaching another ready state or a
terminal outcome. A blank port waits without advancing WINDOW or PERIOD.
END terminates the supplied stream. Input delivery and any buffering by
the outer caller are not included in this component's instruction count.

The finite control counts four completed semiperiod traversals. After
exactly 4h matched places it enters `confirmed_ready`: both halves of the
double palindrome have been checked. A mismatch at place 4h itself is still
`failed_right_dp`. After confirmation, the current left letter L, right
letter R, and PERIOD prediction D select Galil's branches:

| Comparison | Outcome |
| --- | --- |
| L = R = D | Continue matching |
| L = R, D differs | `restart_search` (case 1) |
| L differs, R = D | `chain_shift` (case 2b) |
| L and D both differ from R | `nonchain_move` (case 2a) |

Matching the global first letter produces `palindrome`; this takes
precedence over further matching. END distinguishes `end_pending` from
`end_chain`. These are branch decisions only: actual center movement,
new LOWER preparation, chain-only extension and main1 replay are not yet
implemented. Use `outcomes` for terminal results; the inherited `found`
label is the internal search-to-monitor continuation, not a terminal result.

`make_cancellable` now handles the appended tapes. It derives each tape's
finite work alphabet from the instruction table, treats STATUS and PORT as
scalar cells, and allocates DISTANCE after the other tapes. The wrapped
monitor has 6,450 states and nineteen tapes. Cancellation restores the
original center-tagged WINDOW and LOWER, clears all scratch and returns
all heads to entry positions. Its cost includes the distance monitored
after the search, O(r + largest search window + consumed right places + 1).
Outer cleanup itself is not reentrant. The generalized search-only wrapper
has 1,401 states / seventeen tapes; the older 1,458 count is historical.

Artifacts are `generated/chain-monitor-controller.json` and
`generated/chain-monitor-reusable-controller.json`. Tests execute the saved
base table directly. All 1,080 constructed ternary stream cases passed;
the wrapped monitor used at most nine instructions per consumed place.
Independent review found no issues after 306 base/wrapped cases with
h=1,2,3,5,8,13, 2,439 cancellation boundaries and four scratch-reuse jobs.
All 3,885 syntactically reachable normal wrapped states have cancel entries.

## Sparse transition conditions at the PEG output boundary

`symbolic_tm2peg.py` accepts partial focus guards on a real-time TM. A
transition mentions only the tapes it actually tests or changes. Unmentioned
tapes preserve their focus and head; a `None` write preserves the old focus
while moving. Overlapping guards at the same state/input are rejected.
An absent transition rejects. Tape zippers are bi-infinite, matching the
older `tm2peg.py` model, rather than the finite-controller VM's guarded
nonnegative positions.

The compiler emits one shared `D_i` rule per transition and ordinary PEG
state/focus/zipper rules. It does not enumerate the Cartesian product of
tape focus symbols. As before, the TM reads reverse(input), so the emitted
PEG recognizes the reversal of its input language. The resulting grammar
contains only normal rules, terminals, sequencing, choice and predicates.

This compiler still requires **one TM transition per input symbol**.
Offline instruction programs and a constant number of microsteps per input
need an additional lowering/scheduling construction; passing the current
search/monitor tables directly to it would not establish PAL.

The nineteen-tape marked-palindrome demo (one active tape and eighteen
unchanged tapes) emits 461 rules / 21,187 bytes, rather than enumerating
all focus vectors. `generated/sparse_marked_palindrome.peg` and
`generated/sparse_preserving_move.peg` are checked by the real Scala
Interpreter in `SparseGeneratedFromTmSpec`; Python also compares sparse
and dense TM execution exhaustively through length seven over `ab#`.

Input alphabets are nonempty BMP scalar characters: supplementary characters
and surrogate code units are rejected because Python code points and Scala's
`.` (one UTF-16 unit) would otherwise disagree. Backspace uses `\u0008`,
which the actual grammar reader accepts. Independent review checked 10,200
inputs over forty random machines and identified these two character-boundary
issues; both now have regression tests. Re-review reported no findings.

The three real Scala integration tests pass, including the backspace fixture.
The original two-test run took about 98 seconds. Profiling found repeated
hashing of the immutable global grammar environment in Evaluator. Caching
that exact map's hash per Evaluator (other environments retain their original
hash path) reduced the three-test run to about 1.7 seconds. These are local
observations, not a universal performance claim. Required `sbt test` selected
127 tests and passed; explicit `testOnly *` then ran all 643 tests, all passing.
Logs: `/tmp/macro-peg-pal-sparse-full.log`,
`/tmp/macro-peg-pal-sparse-all.log`.

## Fixed microstep output connection

`PHASE_PEG.md` now describes an additional inverse-expansion compiler. Given
an actual finite machine with a fixed k transitions per input character,
its output can be translated back to ordinary PEG over unchanged input.
The real Interpreter tests include four virtual tape steps, with push/pop
inside a single physical input character. This supplies a construction for
the earlier output-side microstep gap; the complete Galil machine and its
bound still need to be built and justified.
