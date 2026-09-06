# Online control on the scaffold: integration baseline and paced DP

These modules connect real finite FPP instructions to persistent private
tapes and independently movable readonly input heads. They are experimental
components toward the requested plain binary PAL PEG. **That PEG is not yet
complete.** In particular, a constant neighborhood per virtual instruction
does not imply a constant number of instructions per real input character.

## Executable whole-prefix baseline

`scaffold_pal.run(word)` computes a report for every nonempty prefix of a
binary word. It maintains its longest palindromic suffix. A successful
outward comparison extends that suffix. On failure it copies the reversal
of `(old suffix) + (new character)` into SOURCE, executes the actual
finite marked FPP table, selects the largest marked prefix, repositions its
left input head, and cleans the private scratch tapes for reuse.

Why that window suffices: any new palindromic suffix longer than the old
longest suffix plus two would have a longer palindromic interior ending at
the preceding character, contradicting maximality. When the length-plus-two
extension fails, all remaining candidates are inside the copied window.
FPP on its reversal therefore selects exactly the new longest suffix.
The prefix report is true precisely when its left input head is at the
first character. The empty input yields no prefix reports; a future language
entry point must also accept epsilon.

There are no KMP arrays, host coordinates or node identity comparisons in
this control path. `fpp_calls`, per-arrival costs and output collection are
observers and do not feed the transition. Default execution drains all work
after each arrival. This proves no real-time bound. With `budget=k`, precisely
k scaffold ticks run per input character and incomplete work reports zero.
For example `run("aa", budget=3)` reports `[1, 0]`: **bounded work alone misses
a positive**. The regression deliberately preserves that evidence boundary.

For this fixed baseline, independently counted conservative bounds are 391
pointer reads and 396 pointer fields per virtual tick. This uses the input
head library's static bounds, at most two input operations, two private tape
moves, two counter updates, nine private tapes and two counters. Labels use
fixed role names, finite program/mode/symbol values and bounded allocation
slots (less than 64). These are structural bounds, not extrapolations from
observed maxima. The number of virtual ticks is still unbounded per arrival.

Observed costs for the unary family make the remaining problem concrete:

| Input | Total virtual ticks | Most ticks for one arrival |
| --- | ---: | ---: |
| a^8 | 6,700 | 1,479 |
| a^16 | 24,004 | 2,807 |
| a^24 | 51,612 | 4,135 |

All reports in those runs were correct. All three runs observed 70 pointer
reads and 92 fields at most per tick. Increasing the fixed budget merely
pushes the failure boundary outward; the missing periodic-chain mechanism
must remove this repeated growing FPP work.

## Private tape execution

`scaffold_program.ProgramView.step()` executes one row of a fixed finite
read/write/unit-move/halt table. Each tape is a focus symbol plus two
persistent stacks. A left move across its physical origin is rejected.
Table-defined control states are finite even though their identifiers are
represented as short strings in node labels. Observer-only `emit` rows are
not executable scaffold instructions.

An optional fixed `name` separates simultaneous programs' tape and control
fields. `reset()` drops the roots of all the program's private tapes and
restores blank origins in bounded work: the finite table fixes the tape
count. Old scaffold nodes remain immutable. This is a persistent-state reset,
not an assertion that physical TM cleanup takes constant time. The original
baseline still exercises physical cleanup; the new DP search uses root reset.

## Match-paced doubling search

`scaffold_search.SearchView` takes a fixed DP program, a readonly center head,
a separate walker, and a normalized unary match-radius counter. The caller
broadcasts arrivals to both heads and finalizes them every tick. Its entry
assumptions include Galil's main(C,r) conditions and a sufficiently slow
match clock. Those assumptions are **not supplied by this module**.

The first window has radius `ell=8*max(r,1)`. Local copying includes its
center and stops at the input origin. LOWER is constructed from a unary
copy of r. The real finite FPP/DP instructions find the least h greater than
r with palindromic suffix lengths `2h+1` and `4h+1`. OUTPUT retains that h in
unary. A final missed window terminates. A non-final miss waits for match
radius `ell/4`, then doubles the window and begins the next stage.

The debt counter is maintained without numeric addresses:

- Initially it aliases the negative of the current match radius. Each unit
  of `max(r,1)` adds eight span cells and two debt cells.
- Each match event increments radius and decrements debt.
- At a completed stage, debt equals `ell/4 - radius`.
- Once debt reaches zero, doubling consumes each old span cell, creates two
  new span cells, and adds one debt cell per four consumed old cells. During
  this construction, debt is allowed to be temporarily negative; it equals
  the incrementally constructed threshold minus the current match radius.
- A negative debt at stage completion or at the waiting barrier raises an
  explicit deadline error. A miss exactly at zero begins doubling immediately;
  it does not demand an extra match-free tick.

The finite phase counter for division by four is in `0..3`. Each transition
performs a fixed number of head/tape/stack operations. There is no loop over
an unbounded host integer inside a transition. Nevertheless, neither the
fixture pace 1024 nor any other global clock is proved sufficient here.
The tests exercise overlap and reject an actually excessive match rate.

## Virtual places on unchanged input

`scaffold_places.PlaceHead` supplies Galil's odd letter places and even gap
places using one phase bit over `InputHead`. Each real input cell is still
distributed exactly once. A gap after a letter becomes available with that
letter. This permits independent movement and cloning through `a s b s ...`
without padding the external binary input. Search tests execute the `abs`
finite kernel against this view; the source is still the original binary word.

## Verification and remaining connection

The final full Python run passed all 66 tests (58.588 seconds). Required
`sbt test` also succeeded, selecting zero cached tests; the last actual
full Scala execution remains the earlier 646 passing tests in 37 suites.

Run all experimental tests with:

```sh
python -m unittest discover -s docs/notes/palindromes-in-peg -p 'test_*.py'
```

Scoped independent review found and reproduced the exact-deadline defect
before its RED-to-GREEN repair. Final re-review reported no remaining
findings. Additional checks included 28 longer PAL inputs, 42 delayed-budget
cases without false positives, 9,000 private-tape operations, 20 clean FPP
reuse boundaries, 32 ordinary DP searches and 32 virtual-place DP searches
with both letter and gap centers. The signed debt invariant was checked at
29,370 actual search ticks. These are executable checks, not the global
real-time proof.

The next connection is SearchView's unary h to semiperiod preparation,
right-dp synchronization and chain maintenance on the same input-head model.
Chain shifts, restart lower bounds, nonchain center movement and main1 replay
must update those heads/counters together. The whole controller then needs a
fixed actual input-time work bound and a scaffold-to-ordinary-PEG lowering.
The existing sparse compiler consumes a different, single-transition TM
interface; the new Python scaffold controller cannot simply be passed to it.
