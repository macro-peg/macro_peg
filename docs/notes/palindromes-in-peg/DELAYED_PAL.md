# Two overlapping stages for PAL

`delayed_pal.py` is a working indexed reference. It reports the exact PAL
answer for every incoming binary prefix, without an input-length cutoff.
It is not yet an SCA or ordinary PEG.

For a power of two `K >= 2` and `2K <= n < 4K`, split the current prefix as

```
first K characters | middle of length ell=n-2K | last K characters.
```

The prefix is a palindrome exactly when the last block equals the reverse of
the first block and the middle is a palindrome. A fixed-pattern GS matcher
handles the outer equality. The middle characters needed for an answer have
already arrived `K` steps before that answer, providing time for offline work.

A stage is born at arrival `K`, supplies answers on `[2K,4K)`, and is retired
at `4K`. Stages have widths `2,4,8,...`; only two are live simultaneously.
Lengths below four use a finite special case, including epsilon.

There are four offline flag jobs in each stage. For
`b = K/2, K, 3K/2, 2K`, a job becomes available at `K+b` and calculates the
middle-prefix flags for lengths `[b-K/2,b)`. Its earliest answer is needed at
`2K+b-K/2`, giving exactly `K/2` arrivals for service. Jobs are sequential
within a stage. Each emits flags in descending length order; a persistent
stack then supplies them in ascending order. Consume one flag on every
active arrival, including arrivals where the outer matcher returns false.

The indexed job bound in `GS_OVERLAP.md` is
`215b+109 <= 430K+109 <= 512K` for `K>=2`. Thus 1024 indexed steps per arrival
cover each `K/2` release interval. This number is derived for indexed events,
not selected from the largest tested input.

The indexed matcher interleaves two short-prefix comparisons with each new
successful comparison of `v`. A shift restarts that verifier. Since
`|u| < (k-1)/(k-2)*p1` and `|u| < |pattern|/(k-1)`, it finishes by the time a
full candidate match can be reported. The potential `(k+1)*p+q`, together
with preprocessing slack before the first possible match, gives the indexed
service rate 80 used by the reference. It does not claim 80 head moves or
80 scaffold transitions.

The finite fixed-head workers are `gs_match_heads.py` for the outer match
and `gs_dual_flags.py` for the current two-view middle flags. The local
snapshot/view interface, stage controller, flag stacks and fixed local
service are now connected in `scaffold_window_pal.py`. See
[PLAIN_PAL_ARTIFACT.md](PLAIN_PAL_ARTIFACT.md) for the emitted ordinary PEG and
the separate direct-input verification.

Reference validation so far: all prefixes of 33,237 input words (exhaustive
binary lengths through 14, randomized and structured longer cases) agreed
with direct reversal, and all indexed release/answer deadlines held. This
does not by itself certify the local schedule or final PEG.
