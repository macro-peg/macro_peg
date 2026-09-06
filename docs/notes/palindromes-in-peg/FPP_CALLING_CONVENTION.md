# Offline FPP and double-palindrome components

These are finite-tape transducers for Galil's construction. They do not yet
constitute a real-time palindrome recognizer or a plain PEG for PAL.

## Plain word to marked palindrome-prefix tape

`fpp_subroutine.build_marked_program()` expands the existing FPP kernel to
264 states on nine independent single-head tapes. Initially all heads are
at coordinate zero. Only tape 7 (SOURCE) is populated, as `^ word $`; all
other tapes are blank. The binary source alphabet is `ab`. The builder
also accepts a finite source alphabet such as `abs`, reserving `#` for its
private separator and `^`, `$`, `_`, `0`, `1` for work symbols.

All setup is part of the finite controller:

1. Initialize left markers, unary counters and the delta seed `010`.
2. Scan SOURCE to the right, copying its characters to A and B, while
   writing an initially zero mark for each source position on tape 8.
3. At the source end marker, append `#` to A and B, scan SOURCE leftward
   and append its characters in reverse order. Write the work end markers.
4. Rewind the input copies and mark tape. Execute the FPP kernel with every
   A move mirrored by a MARKS move, and every old `emit` replaced by writing
   `1` at the current MARKS cell.

The result on tape 8 is `^ bit_1 ... bit_n $`, where bit_i is one exactly
when the source prefix of length i is a palindrome. The MARKS head ends on
the left marker, ready for a local consumer. Runtime control never receives
an integer border length, calls host string reversal, or allocates a tape.
The test harness decodes the completed output only after the machine halts.

The original kernel requires O(n) transitions; preparation and paired head
moves add O(n). Initialization of blank scratch and the unary seed is
constant work. The input word itself is supplied on SOURCE by the caller.

Artifact: `generated/fpp-marked-controller.json`. Its finite code is also
executed directly by `test_fpp_subroutine.py`, independently of the builder.

## Marked prefixes to the least double-palindrome step

`dp_finite.build_dp_program("abs")` builds a 373-state transducer with
12 independent tapes. SOURCE is as above; tape 10 (LOWER) initially holds
`^ 1^r $` for the strict lower bound r. Every other tape starts blank.
All heads initially scan coordinate zero.

FPP preparation, candidate moves and marking also maintain a second copy of
MARKS on tape 9. After FPP halts, the scanner starts from length 1 on both
mark tapes. For each positive h it advances the first reader twice and the
second reader four times. An end-marker test accompanies every individual
move, so even a skipped end marker causes normal no-result termination.
It consumes one unary LOWER symbol to skip each h <= r. Otherwise it
accepts the first pair of one bits at lengths `2h+1` and `4h+1`.

The success halt state is explicit in the exported table. On success,
tape 11 contains the chosen h in unary. A different halt state reports no
result. Integer h in Python test output is an external decoding of that
unary tape, not a machine register. The scan is monotone and O(n); a bound
larger than the window simply runs into the mark-tape end without a match.

For Galil's suffix-window task, the caller supplies the window in reverse
order. Palindromic prefixes of that reversed window are exactly the
palindromic suffixes of the original window. Source symbol `s` represents
the inter-symbol places, avoiding `_`, which the VM reserves for blank.

Artifact: `generated/dp-place-controller.json`; the artifact itself is
executed by the place-window regression test.

## Verified scope and next connection

The nine-tape FPP passed every binary word through length 16 (131,071
inputs), checking both the prepared copies and every output bit. Maximum
observed transitions/(n+1) was 126.118. The twelve-tape DP passed 27,304
window/lower-bound pairs derived from all binary words through length 11,
with inter-symbol places inserted. Maximum observed transitions/(window
length+1) was 125. Bounds in tests complement the telescoping/monotone cost
arguments; finite exhaustive tests alone are not complexity proofs.

The base programs require blank scratch tapes on entry. The reusable
wrapper below supplies explicit cleanup between calls. Transferring output
marks or unary h to the outer control remains necessary before cleanup.
The SOURCE window must also be copied from Galil's current tapes by local
moves. Galil's doubling stages, cancellation policy, concurrent matching,
chain confirmation and replay still need finite-control code.

In particular, stage3 still uses coordinate arithmetic and Python sets,
and its `run_realtime` counts generator yields, not every `m.tick(h)` unit.
Its old budget 512 cannot certify the new machine. Composition must count
the actual finite transitions and all outer-control work before choosing
a real-time constant or compiling the final PEG.

Regression command:

```sh
python -m unittest discover -s docs/notes/palindromes-in-peg -p 'test_*.py'
```

## Resumption, cancellation, and scratch reuse

`Program.execution(...).step()` executes exactly one finite instruction,
including the final halt. Pausing does not copy or rebuild any tape. The
instruction table itself is unchanged by the VM's ability to pause.

`fpp_reuse.make_reusable(kernel)` wraps the nine-tape marked FPP or the
twelve-tape DP. The resulting binary FPP has 497 states; binary DP has 608;
place-alphabet DP has 702. No additional tapes are required. Entry requires
all heads at their origins and blank scratch. SOURCE (and LOWER for DP)
are supplied as before. The wrapper writes scratch origin markers without
moving a head, then enters the kernel. C's initial zero is the compound
finite symbol `^0`, treated as zero by the kernel and as origin by cleanup.

After consuming a completed result, enter `cleanup`. To cancel at any job
or bootstrap instruction boundary, enter `cancel_entries[current_state]`.
This is a finite control-state dispatch, not a lookup by tape coordinate.
For partial bootstrap, cancellation first finishes the origin writes.
Cancellation during cleanup itself is **not** part of the contract.

Cleanup preserves SOURCE and LOWER contents, erases every scratch tape,
and restores all heads to their origins. Each tape first seeks its origin
locally. Scratch then scans right, erasing its dense live prefix up to the
first blank, and returns left to erase the origin. This depends on the
kernel's dense-prefix invariant, including transient heads on an adjacent
blank cell during push/pop/materialization. It is not arbitrary dirty-tape
recovery. A tape's live prefix and maximum visited position are O(n+1),
so these constant-number sweeps cost O(n+1). Even if LOWER contains many
more symbols, its reader advances only as far as the window scan permits;
cleanup rewinds that reader without scanning the unvisited input suffix.

Verification covered cancellation at all 77,103 instruction boundaries
of every binary DP input through length six (lower bound one), followed
by 500 sequential random/periodic jobs on the same scratch tapes. Maximum
observed cleanup transitions/(n+1) was 120. Independent review checked
3,481 nine-tape cancellation-and-rerun boundaries and compared resumable
execution against the previous VM on 381 runs, with no findings. Tests
also exercise the JSON-loaded kernel and both nine- and twelve-tape reuse.
The input driver changes only SOURCE/LOWER between these test jobs; local
transfer of Galil windows into those tapes remains future work.

## Local windows and geometric search

The next wrapper, `dp_search_finite.build_search_program("abs")`, now
performs that window transfer and the offline doubling search. Its 898
states use sixteen independent tapes, adding WINDOW, SPAN, TEMP and STATUS
to the twelve-tape DP. The exported code is
`generated/dp-search-controller.json`; regression tests load it into the
base VM without executing the builder.

Entry:

- WINDOW contains `^ prefix suffix $`. Its last prefix cell has the
  compound symbol `C:a`, `C:b`, or `C:s`, and its head scans that cell C.
  Prefix is nonempty. The suffix can contain input already seen by the
  caller; the search never reads to the right of C.
- LOWER holds `^1^r$`, its head at the origin. All remaining tapes are
  blank, with their heads at their origins. Creating the center tag and
  providing the initial lower bound belong to the outer controller.

The finite startup copies each LOWER bit into eight SPAN bits; for zero r
it writes eight bits. Thus the first span is `ell=8*max(r,1)`, matching the
behavioral reference. Each stage scans WINDOW leftward from C and copies
the inclusive window `[max(1,C-ell),C]` in reverse to SOURCE. It recognizes
the global left boundary by its symbol, never by comparing positions.
There is one left-neighbor probe beyond the copied window. SOURCE and SPAN
rewind to their origins; WINDOW returns rightward to its center tag before
the reusable kernel starts.

On a miss, kernel cleanup clears its scratch and rewinds LOWER. A separate
local sweep erases SOURCE. If the stage reached the left boundary, search
halts with no result. Otherwise SPAN is doubled through TEMP, its head is
rewound, and the next stage runs on the same physical kernel tapes.
Each stage examines all h>r whose `4h+1` window fits. Hence a first success
over the growing windows is the globally least qualifying h, even when
the initial stages fail.

Success consumes OUTPUT's unary h four times, rewinding between passes,
to move the WINDOW head left and mark exactly C-h, C-2h, C-3h and C-4h.
These cells retain their letters in `P:a`, `P:b`, `P:s`. WINDOW then returns
to C; OUTPUT ends at its origin with unary h preserved. LOWER's contents
are unchanged on either result. Success and miss are distinct halt states.

Initializing SPAN costs O(r+1). Every subsequent stage costs O(ell+1), and
spans grow geometrically. At a found answer or final boundary, the largest
span is O(r + largest copied window + 1). Copying, DP, cleanup, doubling,
and the final four marking passes therefore cost
O(r + largest copied window + 1) in total. This is an offline total-work
argument, not a per-input-symbol real-time bound.

Validation: 32,220 binary prefix/lower pairs through prefix length twelve,
1,000 random/periodic place-prefix cases, explicit first-success witnesses
at h=3,5,9, and direct JSON execution passed. Independent review had no
findings after 819 additional probes. Guarded WINDOW tests at centers 19,
1009 and 100009 all finished in 1,315 instructions and accessed only
offsets -9 through 0, confirming that a nearby answer requires no traversal
of a remote prefix or suffix.

This wrapper has no outer cancellation entry yet. In particular, the
inner kernel's cancellation map cannot be used while the wrapper is
copying, doubling, cleaning, or marking. Reentry after the entire search
also needs outer cleanup of SPAN/TEMP/STATUS and window annotations.
Galil's match-paced stage barriers and saved boundary marks, right-dp,
chain maintenance, nonchain move/replay, and real-time accounting remain
separate work. Immediate offline doubling must not be presented as that
schedule. The explicit PAL PEG remains unbuilt.

## Cancellation of the entire doubling search

`dp_search_reuse.make_cancellable(build_search_program())` supplies that
outer cancellation boundary. The resulting table has 1,458 states and
seventeen tapes. Artifact: `generated/dp-search-reusable-controller.json`.
Entry is the same as above, with the additional DISTANCE tape blank at
its origin. All job instruction boundaries, including intermediate
WINDOW moves, internal cleanup, partial output marking and both final
halt states, have a finite cancellation entry. The outer cleanup itself
is not reentrant.

Two details are essential:

- A WINDOW move updates the extra unary DISTANCE tape. Its head represents
  the current distance left of C; retained one bits represent the furthest
  visited prefix. Cancellation at a half-completed pair first finishes the
  corresponding distance move/write through its finite continuation. No
  address comparison or integer distance reaches machine control.
- Internal deletion writes the compound tombstone `erased:_`, which normal
  read branches treat exactly like blank. Internal cleanup retains origin
  markers. Physical scratch consequently has a dense nonblank prefix even
  when a left-to-right cleanup is only half complete. Outer cleanup treats
  tombstones as occupied and erases through them to the true frontier.
  Merely stopping at the first logical blank failed: at instruction 245
  of the initial test, tape A was `^ _ # a $`, leaving its suffix uncleared.
  The failing regression drove this representation change.

Outer cleanup first returns WINDOW to C, uses the retained extent to scan
and remove any P tags, then returns WINDOW to C again. It rewinds LOWER,
physically blanks all scratch (including origin markers and tombstones),
and leaves every other head at zero. WINDOW's original letters and C tag,
and LOWER's entire contents, survive unchanged. The same physical scratch
can immediately run another job. A consumed successful result can be
cleared with `cleanup`; an interrupted normal state uses
`cancel_entries[state]`.

Normal instrumentation costs a constant per WINDOW move. Cancellation
adds only linear sweeps over the retained extents, bounded by
O(r + largest examined window + 1), including partial initialization and
partial doubling. This still does not give a real-time cancellation bound;
the outer Galil schedule must charge that work appropriately.

Verification: 15,836 wrapped binary prefix/lower cases matched the direct
specification, and 600 random complete-search cancellation points restored
inputs, heads and physical scratch. Every tenth of those cases immediately
reran on its cleaned tapes and matched the original outcome. Maximum
observed reset instructions/(n+r+1) was 112. Independent review found no
issues after 168 normal/unwrapped comparisons and 15,039 additional
instruction-boundary cancellations with nonzero lower bounds. All 931
syntactically reachable normal control states had a cancellation entry.
The full Python suite has 27 passing tests, including JSON execution.

The remaining major connection is now Galil's match-paced scheduling and
its stage-boundary marks, right-dp and chain maintenance, nonchain move /
replay, followed by the real-time proof and TM-to-PEG compilation.
