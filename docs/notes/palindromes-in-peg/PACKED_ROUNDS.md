# Finite service rounds on unchanged input

The implemented route is `build_online -> buffer_source -> pack_service ->
Scaffold.iter_rules`. The final PAL grammar has **not** been emitted. This
document describes the implemented transformations and the checks that have
actually run; it does not certify the source algorithm for arbitrary lengths.

## Source and FIFO boundary

`build_online(q)` has alphabet `ab.`. Here `a` and `b` are actual read events;
`.` is a work event. Boolean fields expose `online.ready` and `online.event`,
and the acceptance field is the value of an output event. Each transition
checks the read/work protocol. Work events are not appended to input heads.

`buffer_source` wraps those equations in a finite persistent FIFO. An arrival
enqueues a pointer to the real input symbol. Each service event advances the
source once, offering a queued symbol only when the source is ready. An idle
source with an empty FIFO keeps its fields and pointer roots. This copying
does not duplicate the queued input or require pointer equality.

The answer resets to zero on an arrival. A completed source output is copied
to the answer only if the FIFO is empty. Once copied, it survives post-output
preparation. This is the finite local implementation of `galil_realtime.py`;
the latter's Python deque is an independent executable reference.

## Packing virtual nodes

`pack_round` accepts a finite list of transitions with a common schema. A
virtual address has a physical node, a finite slot tag, and construction-time
alternatives for cells made in the current round. For slot `i`:

1. The previous root is the preceding virtual slot, or the previous physical
   node's final slot for the first transition.
2. A query of a previously made local slot uses that slot's construction
   equations. A query of an older physical node reads fields selected by its
   slot tag. Conditional addresses preserve both cases.
3. A newly allocated reference is serialized as physical SELF plus its slot
   tag. Old references preserve their physical edge and finite tag.

No transition queries the physical node under construction. Self loops may
be stored, but queries of earlier virtual cells are substituted with their
already available equations. The final slot contains the round's source
configuration. Other slots retain cells that may be reachable from it.

This gives a direct induction on slot number and then on input length:
corresponding virtual nodes have the same Boolean fields, and corresponding
pointer fields lead to corresponding nodes (or both to null). The asymmetric
language tests separately check the reversal in the SCA-to-PEG translation.

The round used for PAL has one arrival slot and `derive(q).service` service
slots. Input predicates are substituted at construction time. The resulting
SCA alphabet is exactly `ab`; no expansion or extra symbol reaches its PEG.

## Executed checks and size

`test_scaffold_round.py` compares complete reachable graphs for widths 1–4,
including conditional pointers, nulls, local push/pop-like references, self
loops, and references retained across rounds. It also checks distinct stage
functions and an asymmetric language. Constant event guards are evaluated
before their disabled branches so compilation avoids needless expansion.

`test_scaffold_event_buffer.py` compares the finite FIFO with a deque oracle
through queue rotations. Its delayed-output example includes post-output
preparation. Packing four slots and compiling the example produced 696,005
ordinary PEG rules (22,279,428 bytes). Rust matched 11 original binary inputs
without `--repeat`, including epsilon, `ab`, `ba`, `abba`, and `baab`; all
agreed with the example's language, epsilon or a word starting with `a`.
This example is not PAL.

For the full source, the command is:

```sh
python3 generate_online_peg.py /tmp/pal-derived-raw.peg \
  --report /tmp/pal-derived-generation.json
```

At q=64 the derived schedule has 6,338 service transitions, plus one arrival.
The first generation attempt built the source (33,874 Boolean fields and
7,412 pointer fields), then was terminated by the 300-second host timeout
while building the FIFO wrapper. It emitted no final PEG. Compiler storage
and packing size are the current obstacle; the recognition schedule must not
be weakened to accommodate a compilation resource limit.

The shared instruction-cell layout subsequently reduced the source to 7,133
Boolean fields and 1,686 pointer fields. Raw instruction cases move at most
one tape, so their cells share an instruction slot while retaining separate
stack roots. External controller moves use distinct slots (eight for DP,
nine for FPP). Full reachable-state comparisons passed for this layout. The
FIFO result has 7,429 Boolean fields and 1,791 pointer fields. A 600-second
attempt reached whole-round packing, but still emitted no final PEG.

For L labels, P pointer fields, and K virtual nodes, the full packer declares
`K * (L + P * ceil(log2 K))` Boolean fields and `K * P` pointers. Here this is
194,683,368 Boolean fields and 11,353,149 pointers, before expression rules.
The dynamic slot reads also increase expression count. These are construction
size calculations, not an input-length limit.

Two alternative representations were measured on the FIFO example:
`scaffold_round_lazy.py` constructs only queried virtual fields, but at seven
service steps still needs 3,150,420 rules. `scaffold_round_tree.py` factors the
same work through binary compositions (using identity stages, not extra
source work); it needs 3,636,361 rules. Neither measurement solves the full
construction's size problem. Constant-field propagation in
`scaffold_optimize.py` is another independently checked experiment; it is
not silently applied to the PAL source.

## Reusing the constructed source

`scaffold_artifact.py` stores finite equations as a flat DAG with integer
child references. It is a compiler checkpoint, **not a PEG**. Loading it
executes no source construction code. Round-trip tests preserve exact PEG
text, sharing, and a 5,000-deep expression graph. The generator rejects a
checkpoint when the source fingerprint or construction parameters differ.

The completed q=64 shared-cell FIFO artifact contains 12,732,790 expression
nodes in 273,939,743 bytes. It was saved in 445 seconds; peak construction RSS
was 6,723,308 KiB. The retained local file is
`/home/mizushima/.codex/artifacts/pal-peg/pal-derived-fifo.sca` (also available
at `/tmp/pal-derived-fifo.sca`). Its SHA-256 is
`bfb1ec16bd75e174a4d75c9afaede44761a62740be9628d6add18229ca95f37e`.

```sh
python3 generate_online_peg.py /tmp/pal-derived-raw.peg \
  --shared-instruction-cells --wrapper-cache /tmp/pal-derived-fifo.sca \
  --wrapper-only --report /tmp/pal-derived-fifo-report.json

python3 generate_online_peg.py /tmp/pal-derived-raw.peg \
  --shared-instruction-cells --wrapper-cache /tmp/pal-derived-fifo.sca \
  --report /tmp/pal-derived-generation-final.json
```

The first command builds or loads the checkpoint only. The second resumes
packing and PEG emission. `--memory-mib` bounds compiler memory; it never
changes recognition behavior. A signal interruption writes a status report
and does not rename a partial grammar into the final output path.

The resumed full generation was then allowed 1,800 seconds with a 16 GiB
compiler address-space limit. It failed in whole-round packing with
`compilation memory limit exceeded`, after 415 seconds reported by the driver
(451 seconds including process cleanup). Peak RSS was 16,615,240 KiB.
`/tmp/pal-derived-raw.peg` does not exist. The retained failure report is
`/home/mizushima/.codex/artifacts/pal-peg/pal-derived-generation-final.json`.
This is an unsuccessful generation attempt, not a tested PAL candidate.

The 16 GiB figure is compiler memory, not the size of an emitted grammar or
a lower bound on the size of any PAL grammar. The large construction follows
from this implementation's full replication of the buffered source state at
6,339 virtual times, together with dynamic slot dispatch. The source timing
bound does not require this particular representation.

A subsequent diagnostic changed finite-program next-state encoding to use
the destination domain directly. At q=4, shared-cell source construction
interned 2,899,913 expressions instead of 2,906,201 (about 0.22% fewer), while
the field counts stayed 5,651 / 1,446. Five circuit/program tests passed, but
this did not address round expansion, so the experimental change was reverted.
Changing q alone also shifts work between layers: q=1, 4, 16, 32, 64 all give
roughly 400,000 scheduled raw instructions per input character. No new full
generation was launched after these measurements.

The final focused pipeline suite ran 41 Python tests successfully, covering
the cost calculations, source contracts, scheduler, circuit state, packing,
and artifact handling. Its full log is retained alongside the artifact as
`pipeline-tests.log`. `sbt test` also succeeded using its cache (zero Scala
tests re-executed). None of these results replaces the missing full PEG
generation, original-input PAL matches, or arbitrary-length argument.
