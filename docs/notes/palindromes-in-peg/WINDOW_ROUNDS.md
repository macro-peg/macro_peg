# Folding bounded work without intermediate heap cells

Status: the counter, position, input-head, flag-packet and finite-table
components below have been implemented and tested through ordinary PEGs.
The complete original-input source is now connected in `scaffold_window_pal.py`;
its final PEG has been emitted and passed 79 unchanged-input checks. The current GS
integration fixture is `scaffold_window_gs_live.py`, using live distance
registers and the original fixed batch-movement instructions.

Directly packing the current PAL microstep machine remains unsuitable.
With the same optimized source, two steps produced 399,937 rules / 13,840,145
bytes; four produced 1,622,436 rules / 56,309,177 bytes. The demand-driven
packer removes dead fields but still duplicates dynamic phase dispatch.
Those measurements are not a reason to generate the entire large round.

The replacement changes the representation of bounded work. An input round
has a fixed instruction bound. Its intermediate states use finite offsets
and selectors. Persistent storage is constructed at round boundaries.

## Counters and coordinate comparisons

`scaffold_window_counter.py` stores an integer as `B*Q+r`, with signed unary
quotient `Q` and centered remainder `-B/2 <= r < B/2`. During a round, the
quotient remains an old pointer pair. Additions change a finite bit vector;
copy and negation copy or exchange the quotient pointers. A construction-time
bound on every copied value's update history is checked. When that bound is
less than `B/2`, the final remainder is strictly between `-B` and `B`.
Normalization adjusts `Q` by at most one and needs at most one new cell per
counter. The bound does not limit the integer's magnitude or the input length.

`scaffold_window_positions.py` goes further for head coordinates. A current
head is an old head origin plus a bounded signed displacement. Moves change
only the displacement; copies change both finite fields. The machine keeps
old pair differences, but does not update those counters at every internal
instruction. If an old difference has nonzero quotient and `B/2` exceeds
twice the movement bound, its sign cannot change during the round. Otherwise
the difference and the displacement correction fit in a finite signed word.
At the end, each new pair difference copies the appropriate old pair, with
orientation, adds the two displacement corrections, and normalizes once.

The signed-word update API takes a separately justified magnitude bound;
it is not an unchecked claim that arbitrary finite bits satisfy that bound.
The position component supplies it from its checked movement histories.

## Input heads

`scaffold_window_stream.py` divides the original input into blocks of fixed
length `B`, greater than the total movement allowed along any head history
in one round. A block stores its endpoint in the original input. A head has
a block zipper, an incoming queue of completed blocks, and a finite offset.

For each old head, the compiler prepares the current block and its immediate
left and right neighbors once. During the round, the head is an old-origin
selector and an extended offset. All intermediate moves, copies and reads
use these three alternatives. Only the chosen final zipper is stored.
Repeatedly crossing the same block boundary does not allocate more cells.

The unfinished last block uses the current arrival endpoint. A move left
from this live block deliberately does not put a live marker on the right
stack: such a marker would become stale after later blocks arrive. Instead,
the empty right side continues to represent the live block, and completion
enqueues its actual endpoint for heads that have moved elsewhere.

Fixed predecessor jumps use shared equations. A jump by `d` from node `n`
returns node `n-d`, including the initial sentinel when appropriate. Splitting
`d-1` into two smaller jumps needs only logarithmically many pointer fields.
The offset bits select those jumps. Reads of the node being created use its
known current input value, preserving the scaffold rule against querying a
new node recursively.

This stream component currently requires consecutive original input
characters. Suspending a logical stream, resetting it at a stage boundary,
and connecting frozen oriented views require an explicit adapter. No adapter
may silently count work markers as letters of the user's word.

## Flag output and control tables

`scaffold_flag_packets.py` stores all flags produced in one round in a single
packet on that round's node. Each fixed push slot stores its bit and the
preceding valid slot index; skipped pushes therefore create no list nodes.
The first valid slot links to the preceding packet. A stack root includes
both the packet pointer and its slot index. Copies preserve both, and a pop
crosses to the preceding packet when necessary. One writer per pool/node is
enforced. There are `O(q log q)` packet labels for capacity `q`, and a fixed
number of pointer fields. The intended PAL consumer pops once per arrival.

`scaffold_rom.py` compiles the instruction table to a shared Boolean decision
diagram. Its columns include instruction operands, successors and live data
registers. Table reads are Boolean equations over finite address bits, not
runtime array lookups. The 746-state GS table decodes with 2,492 shared Boolean
nodes before further control-layout optimization.

## Validation so far

- Counter tests cross positive and negative quotient boundaries, copy and
  negate values, observe signs before normalization, and exercise updates
  of 32,767 and -32,768 in a single original-input round.
- Position tests compare all head orders and decode all persistent pair
  differences against independent integer positions.
- Input-head tests compare exact input-node identities and read symbols,
  including leaving a live block, waiting for completion, and returning.
  Additional Rust PEG checks passed on 106 original protocol strings.
- Packet tests check every stored flag after sparse batches, pops, resets,
  and immutable copies. ROM tests check every GS instruction row and run
  several finite-table steps per character through an ordinary PEG.

The two-head, three-move stream fixture, after projection, has 37,644 rules /
1,105,425 bytes. This is a component measurement, not a PAL grammar size.
Its persistent heap schema grows logarithmically with the movement bound;
the finite transition circuit still grows with the work being performed.


## Live registers and original-input worker integration

`scaffold_window_registers.py` represents an evolving signed register by an
old quotient origin, a reversal bit, and an extended low word. Additions
modify the low word; copying and negation transfer the finite origin and
orientation as well. When the old quotient is nonzero, its signed orientation
determines the sign throughout the bounded round. When it is zero, the
extended low word determines order and equality. A reset uses an invalid
origin to denote the exact zero quotient. Finalization selects quotient
pointers once and performs the single carry normalization.

`scaffold_window_live.py` applies backward liveness and register coloring to
the entire finite GS program. The ROM includes simultaneous register-source,
negation, and fixed-delta columns. A copy reads pre-instruction snapshots of
all source registers. This removes the final all-head-pair cross-product of
the earlier `WindowPositions` implementation. The latter remains as a tested,
more general coordinate component.

`scaffold_window_workers.py` attaches forward and reverse views to one raw
cursor per live data register. Reverse reads are relative offset -1; reverse
moves negate the decoded fixed delta. Every arrival extends the underlying
original-input stream. A stage's segment origin is a raw-end snapshot, and
its flag job takes further raw-end snapshots without changing the stream.
This lets an offline flag job continue reading its frozen word while new
actual input arrives. Logical GS endpoint comparisons preserve the frozen
view bounds. The stream itself needs neither artificial separators nor reset.

The live GS burst fixture with three batch instructions generated 891,595
rules and 30,427,106 bytes. The former dense-coordinate two-unit-instruction
fixture was 2,410,133 rules and 83,523,719 bytes. Eight complete border traces
were checked in the Rust ordinary-PEG runner with the new file; all matched.
The traces still contain explicit loading/work protocol symbols. The whole
original-input PAL artifact must be checked separately after generation.


## Generation memory and ordinary-PEG compaction

The optional expression-interning index is now cleared after each internal
instruction. Live source roots directly own their DAG nodes, so this releases
unused construction temporaries without changing an equation. Availability
queries also avoid constructing endpoint pointers that they never use. The
three-instruction GS artifact before and after these changes is byte-for-byte
identical (SHA-256 `b9eef88ec4dda4f9deb344e531883ec82a65c2672c467c8eda9aa5ea71969aa2`).
The generator releases its construction objects before optimizing the source.

`compact_scaffold_peg.py` then operates solely on the concrete PEG file.
It inlines branch wrapper rules referenced once, removes unreachable rules,
and optionally renames nonterminals bijectively with `--short-names`. Quoted
terminals are excluded from reference scanning and renaming. There is no
algorithm callback or input transformation in this pass.

Applied to the 30,427,106-byte GS fixture, it produced 682,160 rules and
26,047,039 bytes, or 17,646,620 bytes with short names. Both outputs retained
the eight Rust match results. Unit tests cover ordered choice, predicates,
shared rules, and terminals resembling rule names. This is a size reduction
of the validated component file; the full original-input PAL file still
needs its separate final generation and execution checks.

### Ordinary PEG inlining (2026-09-07)

`compact_scaffold_peg.py --short-names --inline-private` also substitutes
single-use E rules with parenthesized bodies. S/B/P boundaries and shared E
rules remain nonterminals. Expansion stops at nesting depth 16 and leaves a
reference there; this limits grammar syntax depth, not accepted input length.
The pass reads/writes ordinary PEG only and does not evaluate the source VM.

The same 3-instruction GS fixture shrank from 891,595 rules / 30,427,106 bytes
to **74,549 rules / 10,118,379 bytes**. All eight previous concrete Rust input
traces retained their answers (`/tmp/window-gs-live-3-private-rust.log`). On
this run loading took 0.59 seconds, versus 5.56 seconds for the earlier
branch-only compacted file. Unit tests cover precedence, predicates, shared
rules, quoted identifiers, and a 1,001-rule private chain beneath a recursive
label rule. These remain component results; the whole raw-input PAL artifact
was still being optimized when measured.

Grouping is now omitted when sequence association preserves the parse, while
prefix predicates, repetition and choices retain the needed scope (including
through aliases). Four inlining tests cover these cases. The same fixture is
now **9,237,597 bytes / 74,549 rules**, and all eight Rust answers still agree
(`/tmp/window-gs-live-3-minimal-rust.log`). The bound is 16 substitution levels;
references beyond it remain rules, independently of input length.

### Whole ordinary PEG artifact (2026-09-07)

Direct emission has completed. `/tmp/pal-window-original.peg` contains
59,169,304 rules / 2,118,673,778 bytes. The source checkpoint is separately
saved at `/tmp/pal-window-original.sca`; it is compiler data, not a PEG.
`rust-peg` now also supplies a `compact-scaffold-peg` binary implementing the
same private-expression substitution as the Python reference. The component
outputs are byte-identical and all eight ordinary-runner answers agree.

On the whole file, this pass produced `/tmp/pal-window-fast.peg` with
**13,248,052 rules / 672,208,000 bytes** in 200.577 seconds. Eight raw-file
matches accept epsilon, `a`, `b`, `aa`, `aba`, `abba`, and reject `ab`, `abab`,
all with repeat=1. The compact grammar passed all 79 unchanged-input checks:
all binary words through length five, 12 additional words through length 33,
and four nonbinary inputs. See [PLAIN_PAL_ARTIFACT.md](PLAIN_PAL_ARTIFACT.md)
for the exact hash, completed logs, reproduction commands and proof scope.
