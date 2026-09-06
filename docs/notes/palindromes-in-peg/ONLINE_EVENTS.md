# Online source events and continuation after a palindrome

This completes the input/output protocol step of
[TRANSLATION_STRATEGY.md](TRANSLATION_STRATEGY.md). It does not complete the
real-time transformation or certify every branch of the source algorithm.

## Executable interface

`OnlineGalil` in `scaffold_galil.py` reuses the existing finite FPP/DP kernels,
place heads, chain controller and center/replay code. It has two operations:

```python
source = OnlineGalil()
result = source.read("a")  # output == 1; input_ready == False
while not result.input_ready:
  result = source.work()  # no character argument
result = source.read("b")
```

Every returned `OnlineStep` contains:

* `output`: `None` for no event, or a completed answer `0` or `1`;
* `input_ready`: whether another source read is permitted;
* `events`: diagnostic counts, not source instructions or semantic actions.

The consumer observes outputs throughout the loop. It may stop as soon as
the answer for its final input is produced; preparing an unused subsequent
read is unnecessary for recognition. Epsilon acceptance remains the initial
language convention and does not require a fictitious read or output event.

`read` rejects a second arrival while the source is busy, including the
post-output preparation interval. `work` rejects calls while waiting for an
input symbol. Invalid calls do not advance the VM. Each valid operation does
one local transition; neither operation loops until a result. No scheduler
budget, external input queue or palindrome oracle is implemented here.

The additional source control consists of two Boolean flags: whether the next
read is permitted and whether the most recent read still owes an output.
These flags have not yet been lowered into the final SCA. They make the
source protocol explicit for that later translation.

## What was wrong with the old boundary

The legacy `run(..., budget=None)` stopped when the right head reached the
latest real letter. Its `step` allowed further matching only when another
real letter was already available. Thus post-output processing of the virtual
trailing gap waited for the **next external arrival**.

For example, after reporting the first `a`, the old controller had `L=R=C=1`.
Only after the next letter was supplied did it move to `L=0,R=2` and start the
fallback that prepares center 2. The old source could produce correct finite
output traces while still hiding this distinction from a proposed scheduler.

The event interface uses `PlaceHead.can_right()`, which allows the internal
gap immediately after a real letter without requiring another real letter.
Output is recognized at a real-letter boundary; input-ready is recognized
at a gap boundary after continuation/replay has completed. Work-only nodes
carry no input character (`input=None`). Input heads retain the actual arrival
nodes, so a work operation does not impersonate or repeat a terminal symbol.

The legacy `step` and `run` retain their old convention for comparing the
earlier symbolic circuits. They are not used as the new source interface.

## Section 8: ordinary continuation

Galil's all-initial-palindromes variant reports a palindrome and, except for
the chain case, continues as after a mismatch. The next tentative center must
exclude the just-reported palindrome.

Our place encoding gives a precise way to do this with the existing FPP.
Let `E(w)` put a gap `s` between consecutive letters of a nonempty word `w`.
After reporting `w`, advance across its trailing virtual gap. The left head
reaches the origin (which is not a gap), causing the continuation mismatch.
The copied FPP window, reversed, is:

```text
reverse(E(w) s)
```

An odd palindromic prefix of this window is either the single gap, or has
the form `s E(reverse(u)) s` for a **proper** palindromic suffix `u` of `w`.
The whole `w` is excluded because there is no leading gap before its first
letter inside the copied window. Conversely every proper palindromic suffix
gives such a prefix. Therefore selecting the longest marked odd prefix selects
the longest proper palindromic suffix; a single gap represents the empty suffix.

If `n=|w|` and that suffix has length `m`, the resulting center has place
`C=2n-m`. After replay/continuation reaches input-ready, `R=2n` and
`L=2(n-m)`. These coordinates are a specification and test oracle only;
the source uses local heads and unary counters to carry out the movement.

For the one-letter prefix, the empty proper suffix gives center 2, consistent
with the paper's initialization that excludes the already-known one-letter
palindrome. The input-ready protocol handles this with no second real letter.

## Section 8: the chain exception

When the existing chain conditions permit a shift, the same virtual-gap
boundary dispatches the chain branch. It advances the center by the unary
semiperiod and retains the periodic continuation instead of invoking FPP.
The output-pending flag prevents post-output matching and shifts from reporting
the same prefix again.

Tests check that on repeated `a`, actual chain shifts take place **after** an
output and **before** the next read, without losing or duplicating answers.
Periodic words, period breaks and replay are checked against an independent
prefix-palindrome predicate. This establishes those tested continuations;
the `main(C,r)` assumptions, right-DP conditions and general timing bounds
are still separate obligations.

## Timing boundary to preserve next

Do not treat `input_ready` as "the answer is available." A positive answer may
already be available while expensive continuation remains. The real-time
scheduler must retain that output and may continue preparation with any work
remaining in its current round.

The predictability cost is measured between output events. In particular,
post-output preparation is included in the work preceding the next output;
it must not disappear merely because it occurs before that input is read.
This corrects the initial plan's identification of emit with awaiting input.

No value of `MATCH_DELAY`, `FPP_QUANTUM` or a real-time service quantum has
been justified by this protocol change. The remaining source contracts and
cost derivations must use the event boundaries above.

## Validation

The five `OnlineGalilTest` tests passed. They cover invalid event ordering
without state advancement, no-output versus output-zero, every binary word
through length five with prefix answers and exact proper-suffix center/head
positions, chain shifts between output and the next read, and longer periodic
inputs with period breaks/replay. These tests also execute work transitions
without supplying any input character.

The seven legacy controller tests and two whole-controller circuit comparison
tests also passed (nine tests). Required `sbt test` exited successfully using
cached results: zero Scala tests were executed in this run. No claim of a
fresh full Scala-suite execution is made.
