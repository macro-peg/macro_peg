# The missing offline procedure: Fischer–Paterson delta tapes

For the subsequent finite input preparation, marked output and one-window
double-palindrome composition, see `FPP_CALLING_CONVENTION.md`. The external
setup/observer limitations below describe the earlier bare kernel.

## Primary source and executable result

Fischer and Paterson, *String-Matching and Other Products*, MIT MAC TM-41
(January 1974), section 3, printed pp. 6–11:
[MIT-hosted scan](https://dspace.mit.edu/server/api/core/bitstreams/aba5a2f0-d38e-4037-83e9-b8958a5bc205/content).
The scan is kept only in `/tmp/macro-peg-fischer-paterson.pdf`, not in Git.

`fpp_tape.py` implements the local-head operations of Algorithm Y. `Head`
exposes only symbol reads/writes and moves by one cell. The algorithm has no
failure-array lookup, address comparison, arbitrary seek, or integer counter.
The VM uses dictionary addresses internally, as any tape simulator must;
addresses do not guide the algorithm. Return values decode head positions
in an external observer. `initial_palindromes` prepares the offline input
`u # reverse(u)` and extracts its entire border chain.

Run the regression suite:

```sh
python -m unittest discover -s docs/notes/palindromes-in-peg -p test_fpp_tape.py
```

`single_head_border_machine` additionally lowers that component to seven
independent single-head tapes. It is **not yet a PAL PEG**, a real-time
recognizer, or a transition table accepted by `tm2peg`.

`fpp_finite.py` now goes one step further: `build_program("ab#")` constructs
an explicit **188-state**, seven-tape offline controller. Its instructions
are read/branch, write, move by one, emit, and halt. Construction-time macros
are fully expanded; the runtime table contains no callbacks, dynamic stack,
integer registers, arbitrary seeks, or head comparisons. The `emit` state
is an observation event only: an external observer decodes the candidate
head position, and never supplies that number back to control.

The controller expects two preloaded independent input tapes
`^ word $`, with A on `^` and B on the first symbol (or `$`). C contains
`010`, scanned at its first cell. S, T, BACK and FRONT each contain only
the left marker `^`. All other cells contain `_`. For FPP, `word` is
`u # reverse(u)`, where `u` is binary. The construction of these input
tapes and writing an output-marker tape are not included in this controller.

Run its independent string-oracle checks with:

```sh
python -m unittest discover -s docs/notes/palindromes-in-peg -p test_fpp_finite.py
```

## Representation and why the earlier KMP rejection was too broad

Use zero-based inclusive endpoints: `P(i)` is the longest proper border
length of `z[0:i+1]`, minus one. Set `P(-1) = P(0) = -1`.
Define `delta(i) = 1 + P(i) - P(i+1)`. These differences are nonnegative,
and their sum telescopes to at most the input length. A tape with one zero
delimiter per entry and `delta(i)` ones therefore has linear length.

At a fallback candidate `p`, a unary counter stores `s = p - P(p)`.
The delta cursor moves left over `s` zero delimiters; every intervening one
decrements the counter. The resulting counter is `P(p) - P(P(p))`, so the
next fallback is available locally. The old counter is copied to a scratch
counter before traversal. On a match, crossing the next delta entry adds
its ones to `s`. The append head records the distance already fallen.
This representation is different from storing isolated fallback gaps: the
delta tape preserves the neighboring information needed after a match.

## Cost argument for the implemented kernel

For finite-control lowering, these are the relevant invariants (all updates
refer to old values on the right):

\[
p=P^{k}(i),\qquad s=p-P(p),\qquad d=P(i)-p.
\]

On a match, the new candidate is `p+1`, the next gap is
`s + delta(p)`, and the finalized difference is `delta(i)=d`.
On failure at the left marker, finalize `delta(i)=d+1`, keeping `p=-1`
and `s=0`. On an ordinary fallback:

\[
p'=p-s,\qquad
s'=s-\sum_{j=p-s}^{p-1}\delta(j),\qquad d'=d+s.
\]

In the implemented representation, `p` is the candidate-head position;
`s` is the unary counter; and `d` is the pending run of ones already
appended after the last delimiter. None is a Python integer controlling
a tape jump. A finite compiler need only retain the current input symbol
and which of the fixed routines is running in its state.

The OCR misses some displayed assignments. They were checked against the
scan's printed page 7 rather than guessed from missing text. The local
Galil reading copy is `/tmp/macro-peg-papers/Galil.ocr.md`; it is OCR text,
not a corrected edition, and remains outside Git.

Each successful extension moves the candidate head right by one; each
fallback moves it left by its old `s`. Hence the sum of all fallback
distances is O(n). During one fallback, the number of crossed delta ones
is at most `s`, because subtracting them leaves a nonnegative new counter.
Counter copying and restoration are O(s), explicitly included in the VM
operation count. Total leftward movement of the delta head is therefore
O(n). Its rightward movement is bounded by this leftward movement plus
the final O(n) tape length. All other loop work is charged to those moves.

Extraction of the final border chain moves the candidate monotonically
left. Its fallback distances telescope again. Output count is at most n.
Thus extraction also takes O(n) operations. Offline input preparation costs
another O(n); it is outside the reported kernel count. The experimental
100/200/300-operation test ceilings are regression checks, not the proof.

## Removing the shared heads without a general simulation theorem

The source uses two heads on the input and two on the append-only delta
tape, plus two single-head counter tapes. Two offline copies suffice for
the read-only input. For the delta tape, keep the materialized prefix on
the reader's own tape and append new symbols to a FIFO queue. When the
reader first reaches a blank cell, dequeue its next symbol and write it
there. Otherwise, read the existing cell normally. The reader can now move
both ways without observing the writer's position or comparing addresses.

The queue uses two independent tape stacks. Appends push onto the back
stack; an empty front stack is refilled by reversing the back stack.
Every appended symbol transfers at most once, so aggregate queue cost is
linear in the number of appended symbols, already O(n). A refill can be
long: this is appropriate for the **offline** FPP component, not a claim
of real-time queue performance. Seven tapes suffice: two input copies,
one materialized delta tape, two queue stacks, two unary counters. No
general multihead-to-single-head simulation theorem is needed for this
particular append-only component.

## Remaining compilation work

The FPP kernel's control flow is now an explicit finite-state transition
system. Its offline initialization and observer contract above must still
be connected to Galil's actual work tapes; local output marking needs to
replace externally decoded integers. A symbolically guarded PEG compiler
is needed to avoid enumerating the full product of tape alphabets.

Galil integration must also count input-window preparation, local output
marking, every scheduler step and each background search, then lower the
finite control and bounded input-time work to PEG rules. The existing stage-3
simulation still uses RAM KMP and Python sets. Its budget-512 result is not
an end-to-end machine proof for this new kernel.
