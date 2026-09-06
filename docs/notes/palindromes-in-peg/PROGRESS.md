# Towards an explicit plain PEG for PAL — progress log

## NEXT SESSION: start here

### 2026-09-07: whole ordinary PEG emitted, 79 unchanged-input checks pass

`/tmp/pal-window-original.peg` is complete: 59,169,304 rules /
2,118,673,778 bytes. The ordinary Rust interpreter accepted epsilon, `a`,
`b`, `aa`, `aba`, `abba` and rejected `ab`, `abab` on unchanged input.
The full raw smoke log is `/tmp/pal-window-raw-smoke.log`.

The new Rust `compact-scaffold-peg` binary matches the Python pass byte for
byte on the 891,595-rule component. On the whole grammar it produced
13,248,052 rules / 672,208,000 bytes in 200.577 seconds. The 79-case suite
passed against `/tmp/pal-window-fast.peg`: all binary words through length
five, 12 more through length 33 and four nonbinary words. All reports have
repeat=1 and the unchanged input's character count. Completed logs and the
manifest are in `generated/window-pal-verification.{log,json}`; see
`PLAIN_PAL_ARTIFACT.md` for the hash, reproduction and proof scope.
The complete source checkpoint also exists; do not rebuild it unnecessarily.
Nine Rust unit tests and `sbt test` pass. Details are at the top of HANDOFF.

### 2026-09-07: emit directly after saving the complete finite source

The complete original-input source was constructed and validated once
(41,298 Boolean fields / 4,291 pointer fields), but its subsequent constant
propagation exceeded the 22 GiB process limit before any full PEG was saved.
The replacement run uses `generate_window_pal.py --checkpoint PATH
--skip-optimize`: it saves the data-only source, then emits the same finite
expressions directly. `--resume PATH` can reuse that source. Raw full-PAL
matching remains unverified until the concrete PEG has finished emitting.

The ordinary PEG postprocessor now supports `--short-names --inline-private`.
It shrank the 3-instruction GS component from 30,427,106 bytes / 891,595 rules
to 10,118,379 bytes / 74,549 rules, with all eight Rust cases unchanged.
The emission storage change also reproduced the previous component file's
SHA-256 exactly, at about 469 MB peak memory instead of 783 MB. Seventeen
tests cover the compiler, checkpoint restart, inlining and stage-clock wiring.

### 2026-09-06 night: whole source on original input, emission pending

`scaffold_window_pal.py` connects the two stages, immutable flag packets,
oriented original-input cursors, and live windowed distance registers.
The complete fixed schedule is 512 matcher and 1,024 batch flag instructions
per real input character (`gs_batch_clock.py`). The finite source is now
implemented; final emitted size and actual raw-PAL PEG matching are pending.
Do not claim the goal complete on the strength of the native observer alone.
The new GS integration artifact shrank to 30.4 MB and passed eight Rust cases.
See the newest HANDOFF section for files, evidence, and remaining work.

### 2026-09-06 late evening: bounded windows replace phase-indexed heaps

See the latest section of `HANDOFF.md`, `WINDOW_ROUNDS.md`, and
`GS_LOCAL_CLOCK.md`. The full delayed PAL local controller is implemented,
but its ordinary PEG still needs repeated input. The old 16,423-step source
passed selected Rust cases through length 16. The new two-view flag worker
reduces the default round to 8,231 steps and its local machine has passed
instruction-by-instruction checks. It preprocesses `u` against `reverse(u)`
at length `b`, avoiding the previous `2b+1` separator view.

Direct packing was measured at about 14 MB for two source steps and 56 MB
for four. Do not extend that expansion to a full round. Counter, head-position,
input-block-window, flag-packet and ROM components now avoid allocating
intermediate heap cells. Their ordinary-PEG component tests passed; the
input-head component also passed 106 Rust cases on original input.
`scaffold_window_gs.py` is the current instruction-burst integration experiment.
It remains to validate that connection, integrate the PAL stage controller,
and emit and verify the final unchanged-input grammar.

### 2026-09-06 evening: compact GS workers and two-stage PAL reference

`DELAYED_PAL.md` and `GS_OVERLAP.md` describe the current construction.
The indexed two-stage PAL reference passed every prefix of 33,237 words and
all indexed deadlines. The fixed-head border/matcher/flag tables are now
implemented. The border table was lowered to a 75,818-rule / 2,445,841-byte
ordinary PEG and directly executed on eight loading/work traces with Rust.
The final unchanged-input PAL grammar remains unbuilt. Snapshot/view loading,
growing-input heads, the local stage controller, and its clock/compact
single-input transition are still required. Do not resume whole Galil expansion.

The latest compiler test covers real loading/rewinding and every instruction,
including copied head symbols and emitted border lengths. Distance tests cover
shared disjoint allocation slots. Nine combined tests passed in 17.886 s;
the subsequent border/matcher/flag five-test group passed in 3.065 s.

### 2026-09-06: compact midpoint construction, superseding the large-generation priority

The user rejected GB-scale expansion as an inappropriate construction for
PAL. Keep the original PAL goal, but do not resume the whole Galil expansion
or treat compiler memory tuning as the main task. PEG/SCA equivalence does
not force that particular algorithm.

`midpoint_peg.py` now emits `generated/midpoint.peg`: fixed ordinary PEG rules
`HalfFloor` and `HalfCeil` returning the two midpoint cuts for arbitrary binary
suffix lengths. It has 9,949 rules / 269,633 bytes; generation measured 0.42 s
and 25,284 KiB peak RSS. See `MIDPOINT.md` for the FIFO invariant and the
three-unit rotation bound. Source states through 1,024 arrivals, ten Python
tests (24 s), and 276 original-input Rust probe cases passed. `sbt test`
passed using the cache (zero Scala tests re-executed).

This is a midpoint component, not PAL: its S wrapper accepts `ab`. The next
missing construction must compare the halves while respecting ordinary PEG
semantics. A midpoint pointer alone does not supply the already consumed
prefix as a continuation. There is no completed PAL grammar.

### 2026-09-06: implement the revised plan

The main/move/replay/chain contract observer, FPP/DP instruction cost formulas,
derived internal clock, and reference real-time scheduler are implemented.
The finite circuit now exposes source read/work/output flags. A finite FIFO
and generic whole-round packing complete the proposed compiler interfaces.
See `FPP_COST.md`, `GALIL_CLOCK.md`, and `PACKED_ROUNDS.md` for their precise
scope and assumptions. The overall arbitrary-length source argument remains
unfinished; test observations do not certify it.

The delayed-output example completed ordinary PEG emission and 11 direct
Rust matches on unchanged inputs. The full PAL construction instead reached
FIFO wrapping and was terminated at 300 seconds, with no final PEG emitted.
The current work reduces compiler expression storage and cell layout while
preserving the derived schedule. `generate_online_peg.py` is the current
driver; old repeated-input generation is superseded.

After changing `Expr` to slot storage, 22 related Python tests passed and two
existing grammar hashes stayed identical. FPP instruction-cell sharing passed
state/tape comparisons at quantum 1 and 3; round short-circuiting passed the
graph/FIFO regression tests. Full controller integration is being checked.

Next: complete full-scale packing and ordinary PEG emission, run raw-input
PAL and non-PAL matches, then finish the composed correctness argument.

The shared-cell source reached 7,133 labels / 1,686 pointer fields; its FIFO
wrapper has 7,429 / 1,791. A 600-second generation attempt reached whole-round
packing and timed out. The full layout requires 194,683,368 Boolean fields
before expression rules. Demand-driven field construction and binary round
composition were checked and measured, but did not resolve this growth.

The complete FIFO is now retained as a flat data artifact: 12,732,790 DAG
nodes, 273,939,743 bytes, at
`/home/mizushima/.codex/artifacts/pal-peg/pal-derived-fifo.sca`.
`generate_online_peg.py --wrapper-cache` resumes from it after checking the
source fingerprint and parameters. `--wrapper-only` prepares the artifact
without emitting a PEG. This artifact is an intermediate finite machine,
not the requested PAL grammar. See `PACKED_ROUNDS.md` for exact commands.

Resuming from that artifact with a 1,800-second window hit the 16 GiB compiler
memory limit during whole-round packing (415 driver seconds, 451 including
cleanup; peak RSS 16,615,240 KiB). No final PEG file exists. The final focused
41-test Python suite passed, as did cached `sbt test` (zero Scala re-executions).
The unresolved work is the full construction in stage 3, raw-input PAL PEG
matching in stage 4, and the combined arbitrary-length argument in stage 5.

### 2026-09-06: restart from the translation contracts

Read [TRANSLATION_STRATEGY.md](TRANSLATION_STRATEGY.md) first. It supersedes
the next-step instructions in the older entries below. The 121 MB grammar
accepts `ab` on original input and is a rejected PAL candidate. Do not tune
its external budget or resume its inverse expansion as the next task.

The current sequence is: explicit online read/work/emit machine, derived
predictability-based real-time scheduler, finite packing of one input round
into one SCA node, and ordinary PEG emission/direct Rust matches. First
source-boundary checks passed on six words with `budget=None`.

The next step is now implemented as `OnlineGalil`: separate `read` and
input-free `work`, output events independent of input-ready, and post-output
gap/continuation work before the next arrival. See [ONLINE_EVENTS.md](ONLINE_EVENTS.md).
Five new tests passed, including all binary words through length five with
prefix outputs and proper-suffix center/head positions. The original plan's
emit-equals-input-ready assumption was corrected. Source main/move contracts,
chain preconditions and all real-time bounds still need auditing.

### 2026-09-05 continuation: whole online scaffold control and paced DP

Start with `SCA_ONLINE_CONTROL.md`. `scaffold_pal.py` now connects matching,
readonly input heads, real finite FPP execution, candidate selection,
repositioning and scratch reuse for all prefix answers. Independent longer
tests agree. It still drains work per arrival: fixed-budget `aa` at budget 3
misses the second positive. This is not real-time PAL or the final PEG.

`scaffold_program.py` runs private finite tape programs on persistent stacks,
with namespaces for simultaneous jobs and bounded root reset. The baseline's
static per-virtual-tick bounds are 391 pointer reads / 396 fields; finite
role names, symbols/states and bounded slots also give finite labels.

`scaffold_search.py` adds actual match-paced DP stages: unary span/debt
counters, local source preparation, real finite DP instructions, ell/4 waiting
barriers and doubling. Review found a zero-debt finish demanded an extra
match-free step; a RED regression fixed immediate doubling at that boundary.
An excessive match rate explicitly fails. A sufficient global pace is still
an assumption, not established by its fixture clock.

`scaffold_places.py` supplies letter/gap places from unchanged binary input
using a finite phase bit. Both readonly movement/cloning and actual DP on
the resulting virtual places pass. Final independent review has no findings;
it additionally checked 29,370 debt/radius ticks, 64 ordinary/virtual DP jobs,
42 delayed-budget PAL cases, and 9,000 tape operations.

Fresh Python suite: all 66 tests pass. Required sbt test succeeds with zero
cached tests selected; earlier actual Scala evidence remains 646 tests.
Next: compose unary DP output with semiperiod/right-dp/chain continuation on
these heads, connect actual shifts/restarts/nonchain move/main1, establish the
global fixed work bound, and lower this whole scaffold controller to PEG.
The existing sparse TM compiler is not a direct emitter for these Python
scaffold views. Goal remains active and unmet; no commit/push.

### 2026-09-05 continuation: clonable online readonly input heads

`SCA_INPUT_HEADS.md` records an additional scaffold composition path.
`scaffold_input.InputHead` uses persistent left/right stacks plus a realtime
incoming queue, allowing independent forward/backward motion and constant
state cloning. No pointer-identity, coordinate, or timestamp comparison is
used in the component. A fixed broadcast barrier and bounded client schedule
are explicit prerequisites; this is not a drop-in TM tape or PEG emitter.

Review found mixed-arrival copies could duplicate a character. RED-to-GREEN
regressions added finite arrival-phase and duplicate-append checks, plus a
same-VM/builder check. Every head must still receive every prior arrival.

- Five scoped tests pass. The longer 10,000-step root test observed at most
  210 pointer reads and 414 pointer fields for three heads / three operations.
- Independent review passed 15,000 adversarial four-head steps and 2,000
  SELF-cell clone steps, including all queue rotation phases.
- Both independently derived bounds are `76H+66N` pointer reads and
  `78H+66N` pointer fields for H heads and N client operations per step.
  Fixed names and the VM's finite slot-label limits remain part of the contract.
- Final review: no remaining findings. All 53 Python tests pass. Required
  sbt test succeeded with zero cached tests selected; actual full Scala
  verification remains the earlier 646 passing tests.

This opens a readonly input-head composition route on the scaffold. Mutable
annotations must be represented separately; global matching/search scheduling,
center/replay integration and finite PEG output for the resulting scaffold
are still unfinished. No full PAL success claim, commit or push.

### 2026-09-05 continuation: local nonchain center movement and cleanup

`MOVE_CENTER.md` specifies the latest 734-state / 13-tape finite component.
It locally reverses a marked interval into FPP input, finds the longest odd
palindromic suffix using output bits, walks the real WINDOW head to its
center, leaves C/RR annotations and clears all scratch to blank/zero.
No coordinate arithmetic or host-selected answer enters its instructions.

Five new regressions pass, including saved JSON and physical scratch reuse.
Another 8,690 guarded intervals passed; no WINDOW read/write escaped the
interval. Independent review passed 144 reuse jobs with mixed annotations,
colon symbols and translated coordinates; the colon observer issue was
fixed from a RED regression. Final re-review: no remaining findings.
Python full suite: 48 tests passed. Required sbt test passed with zero cached
tests selected; the preceding actual full Scala run was 646 tests, all green.

The component has no mid-procedure cancellation and assumes its interval
boundaries are already prepared. Search-head repositioning, main1 replay,
online input handling and the actual global real-time schedule are still
required. This is real center movement, but not the complete PAL PEG.

### 2026-09-05 continuation: bounded virtual microsteps reach ordinary PEG

The latest output contract and argument are in `PHASE_PEG.md`.
`phase_peg.inverse_repeat` compiles G on a fixed-width virtual expansion
into a plain PEG on unchanged input. It splits expression return phases,
preserves ordered-choice commitment and greedy repetition, and computes a
sound may-return fixed point to prune impossible phase branches. No target
validator relaxation or input padding is used.

Real Scala tests now execute a four-step write/right/left/read tape machine
within one input character (k=4) and across two characters (k=2). They also
check recursive balanced matching, repetition and priority/greediness over
all 127 binary strings through length six. Fixtures are reproducible with
`generate_phase_examples.py`; k=2 uses 457 rules / 10,630 bytes, k=4 uses
1,521 rules / 36,826 bytes.

- The initial unpruned Scala attempt was stopped after stack sampling found
  excessive GrammarValidator.leadsToSelf traversal. Phase pruning produced
  the same tested language with ordinary validation in about two seconds.
- Independent differential review: 40,320 initial acyclic comparisons,
  1,260 recursive comparisons and 25,200 further comparisons after pruning.
  Mutation checks distinguish dropped choice/repetition commitment guards.
- Review found that defaulting to the first declaration disagreed with the
  real Interpreter. A RED regression demonstrated this; the default is now
  S, with explicit start override and unknown-start rejection.
- Python full suite passed all 43 tests in 12.339 seconds. Required `sbt test`
  selected the three new phase tests and passed. Full Scala follow-up and
  final re-review are recorded below when observed, not inferred.
- Final evidence: the explicit full Scala run passed all 646 tests across
  37 suites in 65 seconds (`/tmp/macro-peg-phase-all.log`). Final independent
  re-review reported no remaining findings and review-ready for this component.
  Its initial 41,580 and post-pruning 25,200 differential comparisons all passed.

This closes a fixed-microstep *output* construction provided an actual
bounded-step machine exists. It does not supply Galil's missing global
matching/search schedule, input/multihead integration, center movement,
nonchain move/main1 replay, or the real instruction bound. Full binary PAL
PEG remains unfinished. No commit or push made.

### 2026-09-05 continuation: chain decisions and sparse real PEG output

`CHAIN_AND_COMPILER.md` is the newest component contract. The 4,510-state /
18-tape monitor composes real DP output with semiperiod copying, right-dp
confirmation and three-way comparison. Its cancellable wrapper has 6,450
states / 19 tapes; 1,080 constructed cases and independent review passed.
The search-only generalized wrapper is now 1,401 states (older 1,458 counts
below are historical). Branch outcomes still need actual center shifts,
restarts, nonchain movement and replay.

`symbolic_tm2peg.py` emits partial-focus guards without enumerating all tape
symbol combinations. A 19-tape marked-palindrome example is 461 rules /
21,187 bytes and runs in the real Scala Interpreter. Independent random
review checked 10,200 inputs and caught two input-boundary issues: unsupported
backspace escape and supplementary Unicode versus UTF-16 semantics. Both
were fixed with explicit regression tests; input is BMP scalar characters.
Re-review found no issues. It still consumes one TM transition/input symbol.

Profiling the actual PEG integration tests exposed repeated global immutable
grammar hashing in Evaluator. Caching that exact map's hash retained all other
environment behavior and reduced a roughly 98-second two-test run to a
1.7-second three-test run. `sbt test` selected 127 tests and passed, and the
fresh explicit full `testOnly *` run passed all 643 tests in 54 seconds.

Remaining: globally paced matching and search stages, input access, center
movement/restarts, move/main1 replay, actual instruction real-time accounting,
and bounded microstep lowering. A new `phase_peg.py` experiment is testing
inverse uniform character expansion to address the last output-boundary gap;
its final evidence will be recorded separately. None of this is yet a plain
PEG for unmarked binary PAL. No commit or push made.

### 2026-09-05 continuation: cancellation across the complete search

`dp_search_reuse.py` wraps the local doubling search with cancellation at
every normal instruction boundary, including internal cleanup, paired
WINDOW moves and partial result marking. Its 1,458 states / seventeen
tapes are exported in `generated/dp-search-reusable-controller.json`.
All original WINDOW cells and LOWER survive reset, scratch is physically
blanked, WINDOW returns to C and every other head to zero. Reentry uses
those same scratch tapes. Outer cleanup itself is not reentrant.

The first every-boundary test exposed partially erased scratch: A was
`^ _ # a $` at instruction 245. Outer cleanup stopped at the hole. Fixed
by retaining origins and representing internal logical deletion with a
physical tombstone, mapped to blank by normal reads. A separate unary
DISTANCE tape tracks WINDOW's current distance and furthest visited extent;
finite continuations repair half-paired moves before resetting annotations.

- 15,836 wrapped binary prefix/lower cases passed.
- 600 random cancellation points restored all contents/heads/scratch;
  every tenth immediately reran on those same tapes with matching outcome.
  Max observed reset instructions/(n+r+1): 112.
- Independent review: no findings; 168 normal/unwrapped comparisons and
  15,039 boundary cancellations with nonzero lower bounds passed. All 931
  syntactically reachable normal states had cancellation entries.
- Python: 27 tests pass, including generated JSON direct execution.
- Required `sbt test` with server/runtime override passed (zero cached
  tests selected); no fresh full Scala run claimed. Log:
  `/tmp/macro-peg-dp-search-reuse-sbt.log`.

Remaining: match-paced stage barriers and boundary marks, right-dp / chain
maintenance, nonchain move and replay, actual-transition real-time proof,
and symbolic TM-to-PEG output. Offline total work and cancellation are
O(r + largest examined window + 1); this is not a real-time schedule.
The full PAL goal stays active. No commit or push made.

### 2026-09-05 continuation: local doubling windows and semiperiod marks

New `dp_search_finite.py` composes window preparation, reusable DP, geometric
growth, and result transfer into one finite 898-state, sixteen-tape table.
Artifact: `generated/dp-search-controller.json`. WINDOW begins at a tagged
center C; each stage builds reversed SOURCE locally, runs DP, and either
cleans/reuses its scratch before doubling or marks the four semiperiod
positions C-h through C-4h on success. Both outcomes restore WINDOW to C;
successful OUTPUT is unary h with its head at zero. LOWER stays intact.
Read the last section of `FPP_CALLING_CONVENTION.md` for the exact contract.

- 32,220 binary prefix/lower-bound pairs (prefix length <=12) passed.
- 1,000 random/periodic place-prefix cases passed.
- Explicit first-success h=3,5,9 cases exercise multiple doubling stages.
- Independent review: no findings; 819 extra runs plus guarded local-access
  probes. Centers 19,1009,100009 each required 1,315 instructions and only
  accessed WINDOW offsets -9..0. That check is now a regression test.
- Python suite: 24 tests, including builder-independent saved JSON execution.

Total offline work is O(r + largest examined window + 1). Immediate doubling
does not implement Galil's paced stage barriers or retained boundary marks.
Outer cancellation/reentry, right-dp, chain maintenance, nonchain move and
replay, per-input real-time proof, and TM-to-PEG output remain necessary.
The kernel cancel map is not an outer cancellation implementation. No full
PAL grammar, no success claim for the goal, no commit or push.

### 2026-09-05 continuation: resumable instructions and reusable scratch

`fpp_reuse.py` now supplies finite cleanup/cancellation entries for marked
FPP and DP. Read the new section of `FPP_CALLING_CONVENTION.md` for the
contract: blank scratch on initial entry, cancellation at normal/bootstrap
instruction boundaries, preserved input tapes, cleared scratch, all heads
back at their origins. Cleanup uses the kernel's dense-prefix invariant
and costs O(window length+1); arbitrary dirty tapes and cancellation during
cleanup are outside scope. Output must be consumed before cleanup.

- Binary marked FPP: 497 states / nine tapes. Binary DP: 608 states /
  twelve tapes. Place-alphabet DP: 702 states / twelve tapes.
- `Program.execution().step()` runs one actual instruction, with unchanged
  outcomes and accounting. This enables future instruction-level scheduling;
  it does not itself supply Galil's scheduler or a real-time certificate.
- Exhaustive cancellation at 77,103 boundaries over binary inputs <=6,
  lower bound one, passed. Observed max cleanup steps/(n+1): 120.
- 500 sequential random/periodic jobs reused the same scratch dictionaries,
  changing only the input tapes externally between calls; all passed.
- Independent review: no findings. Reviewer additionally checked 3,481
  nine-tape cancellation/rerun boundaries and 381 old/new VM comparisons.
- Python regression suite: 19 tests. `sbt --server --batch test` with the
  runtime-directory override passed, selecting zero cached tests; no fresh
  full Scala run is claimed. Log: `/tmp/macro-peg-fpp-reuse-sbt.log`.

Remaining: local Galil window/output transfer; outer finite doubling,
cancellation policy, concurrent matching, chain confirmation and replay;
per-transition real-time proof; symbolic TM-to-PEG compilation. PAL's
explicit plain PEG remains unachieved. No commits or pushes made.

### 2026-09-05 continuation: counted preparation, marked output, and one-window DP

Previous turn was progress (FPP delta tapes and 188-state kernel). This turn
removed host-side input reversal and integer border output from the next
composition boundary; read `FPP_CALLING_CONVENTION.md` first.

- `fpp_subroutine.py`: 264 states / nine independent tapes. From a plain
  SOURCE tape, prepare both kernel copies and return `^bit_1...bit_n$` on
  MARKS, whose head is at the left marker. Source copying, reversal,
  initialization, rewinding and output writes all count as transitions.
  All 131,071 binary inputs through length 16 passed; max steps/(n+1)
  was 126.118. The JSON table is `generated/fpp-marked-controller.json`.
- `dp_finite.py`: 373 states / twelve tapes for source alphabet `abs`.
  Duplicate marks coherently, consume unary lower bound r, scan at strides
  two and four, and output the least double-palindrome step h>r in unary.
  All 27,304 place-window/lower pairs derived from source lengths <=11
  passed; max steps/(window+1) was 125. The JSON table is
  `generated/dp-place-controller.json`.
- Combined `test_*.py` suite: 14 tests pass, including execution of both
  saved JSON tables. Independent review: no findings at any severity;
  reviewer also checked 285 ternary/random/periodic inputs, both prepared
  tapes, marker-head positions and unary output. Readiness is scoped to
  these offline components with fresh scratch tapes.
- `sbt test` via the native client exited during server startup. Running
  `XDG_RUNTIME_DIR=/tmp/macro-peg-pal-runtime sbt --server --batch test`
  succeeded (sbt2 testQuick selected zero cached tests). The prior explicit
  640-test Scala run still applies to the unchanged Scala source; no fresh
  full Scala run is claimed. Log: `/tmp/macro-peg-fpp-subroutine-sbt-server.log`.

Remaining: dirty-workspace reset; transfer of actual Galil windows and
outputs; finite doubling/cancellation/concurrent matching/chain/replay;
correct per-transition real-time accounting; symbolic TM-to-PEG compilation.
The existing stage3 `run_realtime` counts yields, while `m.tick(h)` can charge
h moves before only one yield. Its 512-yield result is not a full local-move
bound. Keep it as a behavioral reference, not a machine-time certificate.
The complete PAL PEG goal remains unachieved.

### 2026-09-05 update: the offline blocker has a local-head implementation

Read `FISCHER_PATERSON.md` and `fpp_tape.py` first. The older "one task"
description below is superseded: Fischer–Paterson Algorithm Y implements
the failure function through unary *adjacent differences*, not random-access
failure links. Both longest border and the whole border chain are implemented.
The earlier rejection of all KMP-based routes was too broad.

`single_head_border_machine` replaces the append-only two-head delta tape
with a materialized reader prefix and a two-stack FIFO. Together with two
offline input copies and the two unary counters, it uses seven independent
single-head tapes. Every queue item is transferred at most once, preserving
linear total work. Reads, writes, unit moves, counter copy and reset are
counted; input preparation is a separate linear offline pass. The returned
integers are external observer outputs, not machine registers.

The FPP kernel itself is now lowered in `fpp_finite.py` to an explicit
188-state, seven-tape finite controller. The standalone table is
`generated/fpp-offline-controller.json`; its emit events expose head
positions only to the observer, never to machine control. All 131,071
binary strings of length <=16 passed the full FPP check using that finite
controller (maximum steps/(n+1): 94.118). Long-run lengths
83/323/1283/5123/20483 took 6257/24593/97937/391313/1564817 states executed.
`test_fpp_finite.py` checks both generated control flow and execution of
the checked-in JSON table against direct string definitions.

Still required: lower Galil's control flow to a finite transition graph; define
setup/output-marker conventions; replace stage3 RAM KMP and set operations;
account for every step of Galil's real-time scheduling; symbolic transitions;
then compile an actual grammar and test it. The goal is still **not achieved**.
The old budget-512 result does not establish the cost of this new implementation.

Verification so far:

- `python -m unittest discover -s docs/notes/palindromes-in-peg -p test_fpp_tape.py`:
  five tests pass (both tape models, independent brute-force oracles).
- Original multihead FPP: all 131,071 binary words of length <=16 matched
  the exact list of palindrome prefixes; largest counted cost/(n+1) 73.706.
- Longest nonchain border: 5,456 palindromes of length <=21 checked.
- Original multihead complete border chains: 2,000 seeded random/periodic
  inputs of length up to 1,999 matched KMP (oracle only).
- Single-head FPP: all 131,071 binary words of length <=16 matched the
  exact palindrome-prefix list; maximum operations/(n+1) was 95.824.
- Single-head complete border chains: 2,000 seeded random/periodic inputs
  (seed 905, lengths up to 1,999, alphabets ab/abc/abc#$_01) matched KMP.
- On `b^k a b^(k/2) aa b^k`, single-head FPP costs at lengths
  83/323/1283/5123/20483 were 6334/24910/99214/396430/1585294 operations;
  operations/length converged from 76.313 to 77.396 instead of growing
  with the long runs. This is measured evidence alongside the cost argument.
- Repository regression: `sbt test` initially failed before loading because
  `/run/user/1000` did not exist. With a task-local `XDG_RUNTIME_DIR`, it
  succeeded but sbt 2 selected `testQuick` and ran zero cached tests. Therefore
  `XDG_RUNTIME_DIR=/tmp/macro-peg-pal-runtime sbt --batch 'testOnly *'` was
  run explicitly: **640 tests, 35 suites, all passed**, 206 seconds.
  Logs: `/tmp/macro-peg-pal-fpp-sbt.log` and
  `/tmp/macro-peg-pal-fpp-sbt-full.log`. Python suite rerun: five tests pass.
- After finite lowering, the combined `test_fpp*.py` suite passes all eight
  tests, including direct execution of the saved JSON. `sbt test` was run
  again (incremental zero-test selection; the earlier explicit 640-test
  run remains the full Scala regression evidence). No Scala source changed.

The MIT scan is in `/tmp`, not in the repository. Also, finite small-grammar
searches do not prove absence of a small grammar or uniqueness of this route.

### Previous restart point (historical)

**One task blocks everything else**: given a palindrome `W` of length `n` whose smallest
period is greater than `n/2`, find its longest proper border (equivalently its smallest
period) in **O(n) machine steps** — heads, marks, finite control, tapes; no random access.

* Why it is the only blocker: Galil's nonchain case needs exactly this value (the centre
  of the largest initial palindrome of the window), and everything else is built —
  `tm2peg.py` turns a real-time multitape machine into a plain PEG (verified on three
  machines, one of them a palindrome language), and `period_if_periodic` already covers
  the case `period <= n/2`, which is Galil's chain case.
* Why the easy routes fail: KMP needs random access to `fail[]`; O(n log n) breaks the
  predictability accounting (the centre only moves `k/4`, so the budget is O(k) with a
  *constant*); recursing on the first half turns `W` into `Y # reverse(Y)`, which is
  longer than `Y`, so the recursion diverges; the eertree needs a child pointer written
  into an existing node; Manacher needs a comparison of two stored positions.
* Where to look: Fischer & Paterson's linear-time initial-palindrome procedure (the one
  Galil cites), or a constant-space matching technique (Galil–Seiferas, Crochemore–Perrin
  search phase) adapted to report the longest border rather than occurrences.

Once that returns a value, the machine is complete, `tm2peg.py` compiles it, and the
result is an explicit plain PEG for PAL.

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
