# PAL translation strategy, restarted 2026-09-06

This is the current plan. Earlier advice to tune `budget=2048` or finish the
inverse expansion of the existing large grammar is superseded.

The deliverable is one finite ordinary PEG over `{a,b}` that recognizes every
binary palindrome, including epsilon, with no input transformation or length
limit. A generated candidate must be matched on original words before calling
it a working PAL grammar. Finite tests and an arbitrary-length correctness
argument are separate results; neither will be reported as the other.

## Implementation status, 2026-09-06

### Revised priority after the construction-size review

The user rejected continuing the enormous full expansion as a poor route and
asked that compactness inform the construction itself. The four-stage machine
translation below remains an implemented experiment, not the current main
route. Do not resume full generation or spend further work on small compiler
memory reductions without a structural reduction in the construction.

Return to compact PEG equations or a compact SCA designed for palindrome
recognition. Explain what each recursive rule returns and how ordered choice
preserves that invariant before scaling a candidate. The SCA equivalence does
not force Galil's particular algorithm. Existence alone does not prove a small
grammar exists, and neither the failed grammar searches nor this large
translation prove a size lower bound. The original unbounded, plain-PEG goal
and the requirement to test actual original inputs remain unchanged.

The first new component is [MIDPOINT.md](MIDPOINT.md): a 269,633-byte ordinary
PEG returning either midpoint cut for an arbitrary binary suffix. It uses a
small one-node-per-character FIFO construction. It is a pointer component,
not a PAL recognizer; comparing the two halves remains unfinished.

The following paragraphs describe the previous implementation's status.

The source contract auditor, instruction-cost calculation, derived scheduler,
finite FIFO wrapper, and whole-round packing are implemented. See
[FPP_COST.md](FPP_COST.md), [GALIL_CLOCK.md](GALIL_CLOCK.md), and
[PACKED_ROUNDS.md](PACKED_ROUNDS.md). Source correctness for arbitrary lengths
is still a condition of the cost/scheduling argument, not a result established
by the finite contract checks.

The full pipeline is connected by `generate_online_peg.py`. The source and
FIFO have now been constructed and saved, but whole-round packing exceeded
the 600-second attempt. **There is no final raw-input PAL PEG yet.** A delayed-output
example has passed the complete FIFO/packing/ordinary-PEG/Rust route on raw
inputs. The current full layout has about 195 million Boolean fields, before
expression rules. The current task is to reduce that representation, then
emit and match it before completing the overall correctness write-up.

A resumed run using the saved FIFO and a longer time window then exhausted
the 16 GiB compiler memory limit during packing. The focused 41 Python tests
passed; the requested full-generation and PAL-match conditions remain unmet.

## The four translations

```text
Galil's online algorithm, with explicit read/work/output events
    -> buffered real-time machine, using its predictability bound
    -> one-node-per-input-symbol SCA, with finite internal slots
    -> ordinary PEG, recognizing the reversed source language
```

The last reversal preserves PAL. It must still be tested with an asymmetric
source language when checking the general compiler.

### 1. Specify the online source machine

Use Galil's algorithm for **all** initial palindromes, including the change in
section 8, not just the smallest nontrivial initial palindrome algorithm.
The logical output after reading `x` is `1` exactly when `x` is a palindrome.
The initial acceptance handles epsilon; the one-letter case is explicit.

The source interface has three events:

* `read(a)`: permitted only while awaiting the next input symbol; incorporate
  that symbol exactly once and enter the working state.
* `work`: execute one finite local transition without reading another symbol.
* `emit(b)`: complete the answer to that read. This does **not** imply that
  the source is ready for another input; continuation work may remain.

An explicit `input_ready` boundary authorizes the next `read`. In particular,
Galil section 8 reports a palindrome and then continues searching. The
initial version of this plan incorrectly identified output with input-ready;
the source audit below separates them.

All of the source's memory is represented by finite control, tape symbols and
local pointer operations. Host integers, dictionaries and loops may construct
or simulate the machine, but may not provide extra operations to it.

The initial executable reference may run work transitions until `emit`.
That establishes an online interface, **not** a real-time bound. The output
trace, the source's local operations, and its correspondence to the paper are
checked before assigning an input clock to those operations.

### 2. Apply the predictability transformation

Galil section 2 explicitly separates the online algorithm from this step.
Let `d(x,a) = t(xa)-t(x)`, where `t(x)` counts work up to the output for `x`.
This includes continuation work after output `x` and before reading `a`. Obtain a
constant `c` and a predicting function `k` with the paper's conditions:

```text
d(x,a) > c  implies the new output is 0
k(xa) - k(x) >= d(x,a)/c - 2
```

The value `c` comes from bounds for the implemented primitive operations:
FPP, DP stages, chain maintenance, center movement and replay. A tested maximum
on finite strings is not such a bound. The old empirical work ledger does not determine `c`. The current derivation
is in `galil_clock.py`, with its source-contract assumptions stated explicitly.

The scheduler receives every external symbol and buffers it. The source may
read from that buffer only at its input-ready boundary. Within each external
input round it simulates a fixed amount of source work. It emits the completed
current answer when that output has been produced, even if continuation work
remains, and zero when the source is behind in its output. No new
external symbol is silently substituted for a source work step.

The constant is calculable, rather than an empirical tuning parameter. For
example, in an abstract unit-cost source model, `2c` service units suffice:
if output `i` is 1, then `k(i-1)=0` and `d_i <= c`; summing the second
inequality over any preceding interval `j..i-1` gives

```text
sum(d_j .. d_i) <= 2c(i-j) + c <= 2c(i-j+1).
```

This is the work needed from the start of a FIFO busy interval to that
positive answer's deadline. Queue, dispatch, read and output costs must also
be included when lowering this abstract service schedule to local operations.
`galil_clock.py` instantiates this calculation using the implemented source
instruction bounds. The reference and finite-local schedulers are implemented.

### 3. Represent an entire input round in one SCA node

Do not turn each internal operation into another input symbol. Once the
real-time round is a finite control graph with a derived bound, reserve one
finite slot for each possible internal allocation within that round.

A virtual address is represented by `(SCA node, finite slot tag)`. Labels and
pointer fields are indexed by slot. References to earlier slots in the same
round are resolved from their construction expressions; stored references
to earlier rounds use ordinary backward SCA edges plus finite tags.

The compiler must preserve the source configuration after **each complete
input round**, including all intermediate cells that remain reachable. The
slot count and access radius follow from the finite round description. There
is no input-length bound and no pointer-identity test.

This packing pass is implemented in `scaffold_round.py`, with complete
reachable-graph comparisons in `test_scaffold_round.py`. It reuses the
finite-slot and new/old-reference techniques in `scaffold_circuit.py` and
`scaffold_circuit_structs.py`. Full-scale construction remains unfinished.

### 4. Emit and match the ordinary PEG

Feed the resulting SCA equations to `symbolic_sca2peg.py`. Use the existing
Rust ordinary PEG evaluator on the emitted file, with no `--repeat` argument.
Record accepted odd/even palindromes and rejected nonpalindromes, then extend
the comparisons to exhaustive short inputs and structured longer inputs.

The final arbitrary-length argument follows the same interfaces: online
outputs, scheduler deadlines, packed configurations and PEG semantics.
Completing that entire argument is not an extra gate before emitting and
trying a structurally valid candidate.

## First step: source-to-paper audit

The following is a correspondence inventory, not a claim that the existing
controller is already a faithful implementation of every clause.

| Paper requirement | Existing material | What remains to establish |
|---|---|---|
| Section 3, letter/gap places and marked origin | `scaffold_places.py`, `scaffold_input.py` | Preserve the origin and letter/gap distinction through source read events. |
| Section 2, offline initial-palindrome procedure FPP | `fpp_finite.py`, `fpp_subroutine.py` | Reuse the finite program; derive its actual cost for the work bound. |
| Section 5, `main(C,r)` entry conditions and staged DP | `dp_finite.py`, `scaffold_search.py` | Check entry conditions and stage deadlines at every restart; derive the relative schedule. |
| Section 6, right-DP, periodic extension and chain shifts | `scaffold_chain.py` | Map every case, including broken-period restart, to the paper's conditions. |
| Section 7, `move` and offline `main1` until saved `RR` | `scaffold_galil.py` copy/FPP/choose/rewind/replay modes | Establish the new-center bound and the exact continuation at `RR`. |
| Section 8, report all initial palindromes and continue | `OnlineGalil` in `scaffold_galil.py`; [ONLINE_EVENTS.md](ONLINE_EVENTS.md) | Output/read boundary implemented; proper-suffix continuation explained; inherited chain conditions and timing still need checking. |
| Section 2, predictability-based real-time simulation | `galil_realtime.py`, `scaffold_event_buffer.py` | Connect the source-contract argument to the derived schedule for arbitrary lengths. |
| One external symbol per SCA transition | `scaffold_round.py`, `pack_service` | Complete full-scale generation; graph and example-PEG checks have passed. |

`run(word, budget=None)` currently has the useful source boundary: it appends
one input symbol, then calls `step` until `caught`. Its `caught` condition is
scan mode, no replay, a letter position and no unread symbol to the right.
That is the legacy boundary, retained for circuit comparisons. `OnlineGalil`
now provides `read(a)` and input-free `work()` operations, each returning an
optional output event and an independent input-ready flag. It performs
post-output gap/continuation work before admitting the next symbol. A read
may also emit immediately (the first letter), but each offered letter produces
exactly one output event. The implemented buffer obeys this read boundary.

`FPP_QUANTUM` is now the explicit construction granularity q, and
`MATCH_DELAY` is derived from q and the DP instruction bound. The default
q=64 produces M=256; this numerical coincidence does not reuse the old
empirical budget. See `GALIL_CLOCK.md` for the parameterized calculation.

The first boundary check uses `run(word, budget=None)` on epsilon, `a`, `ab`,
`aba`, `abba`, and `abab`. It compares every prefix output, not just the last
bit. The reproducible local result is `/tmp/pal-online-boundary-audit.json`.
These are online-source tests only; no claim about a PAL PEG follows from them.
All six cases matched. In particular, the observed traces were `ab -> [1,0]`,
`aba -> [1,0,1]`, `abba -> [1,0,0,1]`, and `abab -> [1,0,1,0]`.

## Reuse decisions

Keep the finite FPP/DP programs, local head/stack/queue representations,
symbolic SCA-to-PEG compiler and Rust evaluator. Reuse source-controller code
as each row above is matched to its specification. The tests are useful
regressions, with their original scope retained.

The existing `scaffold_circuit_galil.build` models an arrival only once per
`budget` internal steps. Its 121 MB grammar accepts `ab` on unchanged input.
It is a rejected PAL candidate, not the starting machine for the new pipeline.
`phase_peg.inverse_repeat` may remain as a separate general experiment; it is
not the next step of this plan.

## Sources checked for this restart

* Z. Galil, [Palindrome recognition in real time by a multitape Turing machine](https://doi.org/10.1016/0022-0000(78)90042-9),
  JCSS 16(2), 1978, pp. 140–157: section 2 (online versus real time and the
  predictability conditions), sections 5–7 (the source algorithm), section 8
  (the all-initial-palindromes modification). The supplied local PDF is not
  part of the repository deliverable.
* Z. Galil, [String Matching in Real Time](https://doi.org/10.1145/322234.322244),
  JACM 28(1), 1981, pp. 134–149, is the general transformation cited as
  forthcoming in the PAL paper. Its full text has not been retrieved in this
  restart; the abstract and citation do not count as checking its construction.

Immediate next work: reduce compiler storage without changing the derived
schedule, complete whole-round generation, and run the emitted PEG on original
inputs. The contract checks are evidence for selected source traces; they do
not replace the final arbitrary-length argument.
