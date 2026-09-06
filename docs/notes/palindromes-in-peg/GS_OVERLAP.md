# GS overlap and palindrome-prefix workers

Status: the fixed-head overlap worker has been emitted and executed as an
ordinary PEG. It uses an explicit loading/work trace. The complete original-input source is now `scaffold_window_pal.py`; its final
PEG emission and execution are pending. See `GS_LOCAL_CLOCK.md` for the
local and batch service bounds and `WINDOW_ROUNDS.md` for the current
representation and integration evidence.

## Algorithm and source

`gs_overlap.py` is the indexed reference; `gs_events.py` exposes resumable
indexed operations; `gs_heads.py` replaces its cursors by a fixed collection
of local heads and closes the entire finite control into an instruction table.
`gs_flag_heads.py` adds descending palindrome-prefix flags, and
`gs_match_heads.py` implements incremental pattern matching.

The prefix-period decomposition and safe matching shifts follow Galil and
Seiferas, *Time-Space-Optimal String Matching*, JCSS 26 (1983), pp. 280–294,
[paper](https://www.cin.ufpe.br/~paguso/courses/if767/bib/Galil_1983.pdf).
The shrinking right-overlap pass and its use below are this implementation's
construction. They do not rely on extracting a period from the second full
occurrence of a word in its square.

For fixed `k >= 4`, preprocessing finds `x = u v`, where `v` has at most one
basic prefix repeated `k` times. Write `s = |u|`, and let `p1` and `r` be the
remaining prefix period and its reach, when present. The decomposition has
`s < |x|/(k-1)` and `s < (k-1)/(k-2) * p1`. The code uses `k=8` for the
event/head implementations. This is a fixed algorithm parameter; it does not
depend on input length or on observed successful tests.

At a candidate text position, let `q` characters of `v` have matched.
If `k*p1 <= q <= r`, advance by `p1`, retaining `q-p1` matched characters.
Otherwise advance by `max(1, ceil(q/k))` and reset the matched length.
The ceiling convention matters: the potential `(k+1)*p+q` strictly increases
at each such reset, as well as on each successful comparison.

To enumerate proper borders of a word of length `N`, use a stage of length
`m`: pattern `word[:m]`, text `word[N-m:]`. Search only overlaps of length at
least `max(1, 2*s)`. On reaching the text end, compare the short prefix `u`
and report the overlap if it also matches. Then set `m = max(1,2*s)-1`.
The next stage covers exactly the unexamined shorter lengths and shrinks by
less than `2/(k-1)`. The first stage skips the whole word; later stages include
their whole current pattern length.

The direct prefix check is chargeable to the following shift: at a reported
overlap `q >= s`, and either the shift is at least `ceil(q/k)` or it is `p1`
with `s < (k-1)/(k-2)*p1`. Each stage is linear and the stage lengths form a
geometric series. No failure table, word-dependent recursion stack, or stored
list of candidate periods is used by the generator.

For palindrome prefixes of `u`, run the border worker on the internal view
`u # reverse(u)`. Its nonempty proper borders are exactly the nonempty
palindrome prefixes of `u`; the fresh separator rules out longer overlaps.
`gs_flag_heads.py` fills gaps with false bits and includes epsilon explicitly.
The input view is currently preloaded by its observer. A final PAL machine
must build or traverse it internally; `#` is not permitted in the final input.

## What the head implementation establishes

The controller sees only symbol comparisons, head order/equality, head copies,
unit movements, and finite output events. It does not receive integer cursor
positions. All branches of the generator's finite state are closed, including
branches not visited by tests. Bisimulation reduces the resulting table.
`unit_moves` expands bounded batches into single-head unit instructions.

The border table has 468 states before unit expansion and 746 afterward.
The incremental matcher has 457 unit states. The flag worker has 768 unit
states. Generator instances are compilation aids, not runtime primitives of
the resulting table or PEG.

`scaffold_head_distances.py` maintains signed unary differences of the fixed
head positions. A move adjusts the affected differences; a copy copies the
corresponding row/column, reversing signs when required. Equality/order use
these counters, never SCA pointer identity. A saved arbitrary historical head
does not acquire a current distance vector by this mechanism.

`scaffold_gs_heads.py` lowers the unit table, persistent tape zippers, and
distance counters into actual scaffold equations. A one-head instruction
needs at most one cell for each other head, so disjoint instruction cases
share a fixed allocation layout. The standalone fixture loads `a/b/#`, uses
`!` to finish the snapshot, and uses `.` for rewind and work. All loading and
rewinding is represented by actual transitions.

The emitted `/tmp/gs-heads.peg` has 75,818 rules and 2,445,841 bytes. Its start
rule accepts a reversed loading/work trace iff the worker halts after finding
a nonempty proper border. Eight direct Rust PEG executions agreed with the
table observer, including positive `aa/aba/abab/abba` and negative
epsilon/`a/ab/abb`. This is not a claim that its start rule recognizes PAL.

## Indexed bounds versus local bounds

The following accounting is for `gs_events.py`, not for physical head/SCA
instructions. With

```
A(k) = 12k + 7 + (4k+2)/(k-1)
D(k) = 2k + 8 + A(k)*(k-2)/(k*(k-3)),
```

the decomposition accounting gives `D(8)=1125/28 < 41` indexed events per
pattern character, with a conservative job bound `42m+1`. Failed outer
iterations are charged to successive second periods, which grow by at least
`k-2`; the last is at most `m/k`. The final outer iteration is linear in `m`.
Including overlap matching, short-prefix checks, outputs, geometric stage
shortening, and job termination gives the conservative bound `107N+1`.
Filling a flag interval gives `215b+109` indexed steps for a view based on a
word of length `b`.

These indexed bounds explain the reference schedule in `DELAYED_PAL.md`.
They are not a service bound for the head table. The head implementation adds
rewinds, local moves, head comparisons, and copies. That schedule and the
single-original-character SCA transition remain to be completed.

Tests: `test_gs_overlap`, `test_gs_events`, `test_gs_heads`,
`test_gs_match_heads`, `test_gs_flag_heads`, `test_scaffold_head_distances`,
and `test_scaffold_gs_heads`. Independent exhaustive/random runs described in
the progress log supplement them; finite tests alone do not prove arbitrary
length correctness or deadlines.
