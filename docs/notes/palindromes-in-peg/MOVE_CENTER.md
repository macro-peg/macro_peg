# Local nonchain center selection

`move_center_finite.build_move_center_program("abs")` exports 734 states on
thirteen independent tapes. It implements the FPP-based selection and local
WINDOW movement inside Galil's nonchain move, including scratch cleanup.
It is not the entire move/main1 procedure or the complete PAL machine.

WINDOW (tape 12) contains a nonempty interval. Its left/right endpoint tags
are `ML:x` / `MR:x`, or `MLR:x` for a singleton. The head starts at the right
endpoint. Interior cells are source symbols, optionally annotated C/P/RR/CRR.
All other tapes start blank with heads at zero. Boundary preparation is the
caller's responsibility; WINDOW cells outside the interval are not inspected.

The finite controller copies the reversed interval to SOURCE one symbol and
one local head move at a time, clears old interval annotations while returning
WINDOW to the right endpoint, and calls the reusable FPP kernel. It marks
the first FPP output cell, scans the output to its end while remembering parity,
then scans backward to the largest odd prefix length whose palindrome bit is
one. The first cell is always such a candidate, so no empty candidate occurs.

For selected length ell, two backward MARKS moves correspond to one backward
WINDOW move. Reaching the marked first cell leaves WINDOW at
`right - (ell - 1)/2`, the center of the longest odd palindromic suffix.
That equation is an external specification, not a runtime integer operation.
For Galil's alternating character/space places, this is the appropriate
place-centered candidate. The controller does not verify the outer nonchain
hypothesis or claim the center-advance lower bound without that hypothesis.

The result retains a C tag at the new center and RR at the old right boundary
(CRR when coincident), preserving every source symbol. The temporary first
mark is restored, kernel cleanup runs, and SOURCE is also physically erased.
Every scratch head returns to zero and all scratch cells are blank. WINDOW
remains at C. All copying, parity scans, movement and cleanup use O(n) local
instructions for interval length n, independently of its absolute position.
Mid-procedure cancellation is not supported.

Evidence:

- Five Python regressions cover 1,022 binary intervals through length nine,
  longer/place inputs, absolute-coordinate translation, colon symbols,
  direct saved-table execution, and repeated physical scratch reuse.
- An extra 8,690 exhaustive/random intervals passed with guarded WINDOW
  storage rejecting every out-of-interval read/write. The largest observed
  instructions/(n+1) was about 198.77; this is an observation, not a proof bound.
- Independent review passed 144 successive scratch reuses with mixed tags,
  colon symbols and lengths through 513. Guarded positions 0, 1, 1,000,000
  produced identical work counts. A colon observer-decoding defect was fixed
  with a RED-to-GREEN regression; final review reported no remaining findings.
- All 48 Python tests passed. Required `sbt test` succeeded but selected zero
  cached tests; the latest actual full Scala run remains 646 passing tests
  from the preceding phase compiler verification.

The executable artifact is `generated/move-center-controller.json`.
Remaining integration includes boundary preparation, search-head relocation,
chain state reconstruction through main1, online input availability and the
complete real-time schedule. The runtime observer never supplies the answer.
