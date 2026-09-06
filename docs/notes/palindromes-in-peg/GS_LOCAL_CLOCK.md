# Local instruction accounting for the GS workers

The newer two-view flag worker uses `gs_local_clock.DEFAULT_DUAL`: matcher
2,048, flags 8,192, overhead 39, total 8,231 physical transitions per arrival.
`scaffold_delayed_pal.build()` now selects this version by default. The older
separator-mirror version remains available with `dual_flags=False`.

`gs_local_clock.derive(8)` selects 2,048 matcher transitions and 16,384 flag
transitions per arrival. The existing `scaffold_delayed_pal` controller adds
39 transitions for arrival, queue maintenance, broadcast and stage control,
giving a round of 16,423 transitions. Its emitted PEG still requires that
fixed input repetition; it is not a grammar for unchanged binary PAL.

The following accounting is for the finite tables after `unit_moves`. A
symbol/order test, copy, flag, halt or one head's unit movement costs one.
Integer coordinates in the test VMs are observers, not constant-time source
operations. Queue maintenance adds the separate factor of four below.

## Decomposition

Let `k >= 4`, `m` be the current pattern length, `p` the shift, and `q` the
matched suffix length. During a reset the total rewind `R` is at most `k S`,
where `S` is the total shift distance: the shift is `max(1, ceil(q/k))`.
If `M` successful symbol comparisons occur, `M = Q + R` in `_first`, where
`Q` is the final matched length. The explicit loops give

```
first <= k + 14 + bounded + 5 Q + (8 k + 9 + bounded) S.
```

The constant includes initialization and loop exits. At a successful period
`p`, `Q=(k-1)p`, `S=p-1`; on unbounded failure `Q=0`, `S<=m-1`.
The bounded search can overshoot its bound `b`, but by less than a factor of
two. These yield the convenient bounds `(13k+4)p` for a found first period,
`(8k+9)m` on unbounded failure, and `(16k+20)b` on bounded failure. A bounded
successful search costs at most `(13k+5)p`.

For `_second`, divide shifts into reset distance `S_r` and periodic distance
`S_p`. Here `M=Q+R+S_p`. Each successful comparison costs at most six table
instructions. Reset work costs `2R+(k+1)S_r` plus three per reset; periodic
work costs `(k+4)S_p` plus two per shift. Counting tests gives

```
second <= k + 14 + 6 Q + (9k+9)(S_r+S_p).
```

The GS overlap lemma used in `GS_OVERLAP.md` gives `p2 >= (k-2)p1` and
`reach <= p2+p1` after finding a second period. Deleting the short prefix
costs at most `(29k+28)p2`: successful bounded searches and their deletions
sum to less than `p2`, followed by one unsuccessful bounded search. Combining
the first search, extension, second search and deletion gives a failed outer
iteration cost bounded by

```
F(k) p2,  F(k) = 44k + 31 + 13k/(k-2).
```

Successive second periods grow by at least `k-2`, and the last is at most
`m/k`. The final outer iteration costs at most
`(9k+22+4/k)m`. Therefore use

```
D(k) = ceil(9k+22+4/k + F(k)(k-2)/(k(k-3))) + 5.
D(8) = 160.
```

The extra five per character cover entry/exit instructions and rounding;
they are part of the chosen bound, not a measured maximum.

## Border and flag work

In one overlap stage, successful comparisons and rewinds charge at most
`(7k+15)m`. Each completed short-prefix check costs at most `4s+6`, where `s`
is the cut length. For a reset, `s <= k` times the following shift; for a
periodic shift the overlap lemma gives a stronger bound. Thus all such
checks charge at most `(4k+6)m`.

Initialization, constructing the end of the search interval, and shrinking
the next interval cost at most `23m`. In particular these include the actual
loops that move `Second` twice per deleted character; they are not treated
as arithmetic assignments. The next stage has length less than `2m/(k-1)`.
Summing the geometric sequence gives

```
border coefficient = (D(k) + (7k+15) + (4k+6) + 23)(k-1)/(k-3).
```

Filling omitted flag lengths takes at most six additional instructions per
view character, with five more for entry/exit. The rounded coefficient in
the code is `C(8)=420`. A flag job on `u # reverse(u)` therefore fits within
`C(2|u|+1)+1` head instructions. The current PAL schedule has `|u|<=2K`,
`K>=2`, and a service interval of `K/2` arrivals. Consequently

```
C(4K+1)+1 <= (4.5C+0.5)K.
```

One head instruction can request one queue operation, followed by three
maintenance transitions. Four physical transitions per head instruction
therefore require a per-arrival rate at least `8(4.5C+0.5)`. Rounding that up
to a power of two gives 16,384. Idle/done transitions fill unused service.

## Matcher work and arrival deadlines

Use the progress quantity `Phi=2kp+q` for the streaming matcher. A successful
comparison, its at-most-two prefix checks, and output checks cost at most
16 instructions per unit progress. Reset and periodic-shift loops charge to
the shift increase in the same quantity. Between a wait at text frontier
`i` and a positive result at frontier `n`, progress is at most `2k(n-i)`.
This contributes at most `32k` instructions per arriving character.

Decomposition costs at most `D(k)m`, and initialization/short-prefix offset
handling is covered by another `16m`. No match is possible before `m` text
characters arrive. Hence the chosen logical rate is the next power of two
above `D(k)+32k+16`, namely 512 at `k=8`. Queue service gives physical rate
2,048. The matcher also checks that a positive output refers to the current
arrival frontier; a delayed older match is an invariant failure.

These are written loop-accounting arguments. Native clock tests and emitted
PEG tests provide differential evidence, not a mechanized proof. The newer
window counter/head components remove intermediate allocation in a different
representation. That replacement is now connected in `scaffold_window_pal.py`;
the final section below gives its fixed batch rates, and `PLAIN_PAL_ARTIFACT.md`
records the completed unchanged-input checks of its emitted PEG.

## Two oriented views

`gs_dual_flags.py` uses pattern `u` and text `reverse(u)`, each of coordinate
length `b`. Heads carry a view as well as a coordinate; copies preserve the
view. `Origin` is the forward origin, `TextOrigin` the reverse origin, and
`OriginalEnd` the reverse end. Only the initial assignment of `Tail` changes
in the GS control. Decomposition still reads the pattern, and overlap checks
compare the pattern prefix with the text suffix. An overlap of length `ell`
is precisely `u[:ell] == reverse(u[:ell])`.

After shrinking to `m`, the pattern is `u[:m]` and the remaining text suffix
is `reverse(u[:m])`, so the same overlap invariant recurs. The proper-overlap
interface reports only lengths below `b`, exactly the job intervals used by
the delayed PAL controller. This removes the `2b+1` preprocessing length;
it does not infer a smaller rate just from timings.

The same loop bound gives `C*b+1` head instructions. With `b<=2K`, `K>=2`,
this is at most `(2C+0.5)K`. Multiplying by four for queue service and dividing
by `K/2` arrivals requires rate `8(2C+0.5)`, or 6,724 at `C=420`. The selected
power-of-two rate is 8,192. The native differential run checked 12,474
intervals over 4,158 words, including long periodic cases; the observed
maximum is evidence only and is not the chosen bound.


## Window backend: fixed batch instructions and interval stop

`gs_batch_clock.DEFAULT_BATCH` selects 512 matcher instructions and 1,024
flag instructions per actual input character. `scaffold_window_workers.py`
executes the original finite batch movements: the operands such as `k` and
`-2` are fixed constants, and the window's finite low words apply them without
intermediate heap cells. Block-queue preparation occurs once per actual
character, outside this instruction burst. The earlier physical factor of
four is therefore absent in this backend.

The batch decomposition count follows the same loop charges above, counting
one complete fixed move tuple as one instruction. In `_first`, initialization
costs six, the successful comparison path costs four, a reset costs `2R+S`,
and its three outer tests give

```
first <= 15 + 4Q + (6k+9)S.
```

A bounded search adds one test per outer iteration. Convenient resulting
bounds are `(10k+5)p` on first-period success, `(10k+6)p` on bounded success,
`(6k+9)m` on unbounded failure, and `(12k+20)b` on bounded failure. For
`_second`, the successful path costs five and reset/periodic moves cost
`2R+S_r+2S_p`. Counting the outer tests gives

```
second <= 15 + 5Q + (7k+9)(S_r+S_p).
```

Charging successful bounded deletions to their total shift, then adding the
last failed bounded search, gives the failed-outer coefficient
`34k+32+(10k+2)/(k-2)`. The final outer coefficient is `7k+19+5/k`. Thus use

```
D_batch(k) = ceil(7k+19+5/k
                 + (34k+32+(10k+2)/(k-2))(k-2)/(k(k-3))) + 5.
D_batch(8) = 129.
```

In one overlap stage the scan charges at most `(5k+12)m`; completed
short-prefix checks cost `3s+6` each, totaling at most `(3k+6)m`. Setup,
interval construction and shrink cost at most `23m`, including the new
lower-endpoint test. Filling flags and entry/exit contribute another `11m`.
The single-stage coefficient is consequently `129+52+30+23+11 = 245`.

The dual-view controller now stops when its next `End` is strictly below
`Lower`. Equality does not stop: that endpoint may still need a flag. Job
`j` has view length `b=jh`, lower endpoint `(j-1)h`, and `h` service arrivals.
For jobs 2, 3 and 4, the next stage is below `2b/7 < b/2 <= Lower`; only the
first overlap stage is needed. Their work is bounded by `245jh+1`. Job 1
still sums the geometric stages, with coefficient
`ceil((129+52+30+23)*7/5)+11 = 339`. Hence a rate of at least
`max(339, 4*245+1)=981` suffices; the selected power of two is 1,024.

The matcher conservatively retains its previous logical rate of 512: batch
execution cannot cost more instructions than the verified unit expansion.
No tighter matcher estimate is needed for this change.

`test_gs_batch_clock.py` checks all four job forms against direct palindrome
prefixes. `test_gs_batch_pal.py` independently runs the full two-stage
schedule with integer-coordinate observers, including all binary words of
length at most eight and long/random inputs. It checks every prefix answer,
flag completion before consumption, and the exact arrival of each positive
match. These tests supplement the loop accounting; they are not a proof by
sampling and do not themselves execute the emitted PEG.
