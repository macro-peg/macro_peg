# Finite-table instruction bound

`fpp_cost.py` derives the control-graph factor and combines the resource
ledger below. Counts refer to actual `fpp_finite.Program` instructions,
including reads, writes, unit moves and the final halt. They exclude the
host simulator and do not use a measured maximum to select a constant.

Let `N` be the bare kernel input length, `J=max(N-1,0)` the number of
processed symbols after the first, and `M<=J` the number of successful
extensions. Each successful extension increases A's logical candidate by
one. A failed comparison's trial move and undo cancel. Every fallback
decreases that candidate by its positive old distance `s`. The final border
extraction also performs these fallbacks, and leaves A at zero. Thus the
sum of **all** fallback distances is `M<=N`. In particular, their number
is at most N. Failed comparisons during processing number at most J:
their fallbacks append the distance in ones, and left-end failure appends
one more; the total appended ones telescope to `J-final_candidate<=J`.

The lazy delta queue receives at most J zero delimiters and J ones. Each
symbol is pushed, transferred, and dequeued at most once. A nonempty refill
is followed by a dequeue. Consequently FRONT is read at most twice per
dequeue, hence at most 4N times.

| Charged instructions | Upper bound | Reason |
|---|---:|---|
| A moves | 4N | At most 2N trials, N undos, N total fallback distance. |
| B moves | N | One forward scan. |
| C moves | 6N+1 | Left moves cross N fallback zeros and at most N ones. Right moves are at most left moves plus the materialized delta extent, at most 2N+1. |
| S moves | 7N+1 | Copy and restore cost `2s+2` per fallback, totaling at most 4N. Pops cost at most N. Pushes while scanning right cost at most leftward one crossings plus the delta extent in ones, at most 2N+1. |
| T moves | 2N | One push and pop per copied unit of s. |
| BACK moves | 4N | At most 2N pushes and transfers. |
| FRONT moves | 4N | At most 2N transfer pushes and dequeues. |
| FRONT reads | 4N | Dequeues plus nonempty refills. |
| Emits | N | Strictly decreasing positive border lengths. |
| Halt | 1 | The completed execution. |

The bound on ones crossed in one fallback follows from the delta invariant:
the old nonnegative counter `s` is decreased once for each such one and
finishes nonnegative. C begins at position zero. Its rightward extent is
bounded by the initialized `010` and the appended deltas; the empty input
performs no tape moves. These are the Algorithm Y invariants explained in
[FISCHER_PATERSON.md](FISCHER_PATERSON.md).

Cut the finite control graph at each charged instruction. The remaining
graph is acyclic, and the longest path up to and including a cut has length
4 for the generated kernel. `cut_distance` checks every branch, including
infeasible combinations, and rejects an uncharged cycle. Partitioning any
completed execution at its cuts gives

```text
kernel(N) <= 4 * (33N + 3) = 132N + 12.
```

This graph check does not by itself prove the resource ledger. The ledger
uses the tape invariants above; tests additionally check every resource
individually on finite executions.

For the marked subroutine, input length m becomes `N=2m+1`. Preparation
takes exactly `24m+42` instructions: initialization 18, forward copying
`8m`, turn 7, backward copying `6m`, terminal writes 3, and rewinding
`10m+14`. Each A move in the kernel adds one MARKS move, at most 4N;
replacing emit by write has no extra cost. Therefore

```text
marked(m) <= 136(2m+1) + 12 + 24m + 42 = 296m + 190.
```

DP duplicates each MARKS movement/write on SECOND. Setup adds `3m+4`;
kernel duplication adds at most `4N+N`, for A moves and emitted marks.
After replacing the kernel halt by one read, the scan costs at most
`18m+25`: initialization 6, at most `m+1` attempts of at most 18 local
instructions, and a final halt. The unary lower bound is read only as
candidates advance, so its possibly larger length does not increase this
bound. Thus

```text
dp(m) <= marked(m) + 13m + 9 + 18m + 25 = 327m + 224.
```

These bounds cover fresh persistent scratch tapes, as used by the source.
They do not cover the separate mutable-tape cleanup/reuse experiments.
