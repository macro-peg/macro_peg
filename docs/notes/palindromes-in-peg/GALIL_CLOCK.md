# From finite instructions to source service

`galil_clock.derive(q)` computes a match interval and a proposed service
bound from [FPP_COST.md](FPP_COST.md). The positive integer q is the number
of finite instructions batched in one source transition. It is a compilation
granularity; choosing another q changes the derived interval. No observed
input maximum is an argument to this calculation. The existing q=64 gives
stage factor 10, match interval 256, and predictability constant 3169.

The bounds below rely on the source contracts (including Galil's chain and
move lemmas), not just the instruction table. `galil_contracts.py` checks
those boundaries on finite traces. A complete arbitrary-length verification
of the implemented source remains separate from these tests.

## DP stages and right-DP preparation

Write ell for a stage span, m for the copied window length, and r for the
main lower bound. Initially `ell=8*max(r,1)` and later ell doubles. In every
stage `m<=ell+1` and `r<=ell/8`. Count every mode transition as well as
copies, rewinds, and the finite DP program. The first stage costs at most

```text
max(r,1) + 2r + 2m + 7 + ceil((327m+224)/q).
```

A later stage replaces the first term by `ell/2`. Dividing by ell and
using ell>=8 initially and ell>=16 later gives the two rational expressions
in `derive`; their ceilings yield a uniform factor K with stage cost<=K*ell.

At initial main entry, `radius<=5r/3`, whereas the first barrier is `2r`.
The slack is at least `ell/24`; for r=0 it is larger. The initial match
clock is reset. Choosing match interval `M>=24K` therefore completes the
stage by its barrier. A subsequent stage starts at radius `ell/8` and ends
by radius `ell/4`; its integral slack is ell/8, so even an immediately due
comparison is covered by the same M. `derive` rounds M upward to a power
of two, to use the existing binary clock circuit.

The smallest DP found has step h>r initially, or h>ell/8 after the previous
stage failed. Its discovery is before radius 2h, allowing for the possible
comparison in the discovery transition. The r=0 first stage finishes before
its first comparison (its cost is less than M). Copying the h-symbol
semiperiod and returning its head take `2h+2` transitions. Catching up the
verifier takes one transition per already matched place, including at most
one new place per M transitions. With M>=8, it catches up before the radius
reaches 4h. Thereafter each actual match advances the verifier in the same
transition. Thus a broken confirmed period is detected on that comparison,
and its main restart occurs in the next transition.

## Cost between consecutive outputs

Count source transitions, not external input rounds. There are exactly two
new places between consecutive letters: the previous letter's trailing gap
and the next letter. A mismatch at either place starts one move or chain
shift. Replay returns to the saved place and cannot mismatch inside the
FPP-selected palindrome. These facts are source contracts checked by the
boundary observer.

For a move that advances C by delta, Galil's move inequality gives old
radius `k<=4*delta`. The copied window has `m=2k<=8*delta`. Copy, home,
marker search and rewind cost at most `5m+6`; marked FPP costs
`ceil((296m+190)/q)`. Replay takes at most `2M` transitions per place,
including a possible confirmed-chain restart after each comparison. Thus
the coefficient of delta is

```text
alpha = 8M + 40 + ceil(8*296/q).
```

Each move has residual overhead `6+ceil(190/q)`. A chain shift takes
delta+1, within the same bound. The two new-place comparisons cost at most
4M. Including two residual overheads and two dispatch units gives beta,
`interval_overhead` in the code. Hence an interval with total center advance
delta has cost `d<=alpha*delta+beta`. The first one-letter output costs one.

## Predictability and FIFO service

At the output for prefix length i, let C_i be the current tentative center
in place coordinates and set

```text
k_i = max(C_i - i - 1, 0),  c = alpha + beta.
```

The source center invariant excludes unreported initial palindromes centered
strictly left of C_i. The next k_i answers are therefore zero. A positive
answer has C_i=i and k_i=0. Centers never decrease. Writing
`delta=C_(i+1)-C_i`, the prediction gain is at least delta-2, and is at
least -1 when delta=0. For delta>=1, `d/c<=delta`; for delta=0, `d/c<=1`.
This establishes the paper's second inequality. If the next answer is
positive, delta<=1, so d<=c, establishing the first inequality.

Serve source work FIFO at rate 2c per external input round. If answer i is
positive, k_(i-1)=0 and d_i<=c. For any busy interval j..i, telescoping gives

```text
sum(d_j..d_i) <= 2c(i-j) + c <= 2c(i-j+1).
```

The positive answer therefore meets its deadline. When the source has not
produced the current answer, return zero. The source must still finish each
read and its post-output preparation in order; output is not a read boundary.
The buffer and dispatcher add bounded local fields/operations to a source
transition, and must be represented in the actual SCA wrapper. Python's
deque reference scheduler alone does not satisfy that last requirement.
