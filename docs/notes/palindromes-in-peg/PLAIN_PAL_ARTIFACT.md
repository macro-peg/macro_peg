# Concrete ordinary PEG for binary PAL

The complete candidate is `/tmp/pal-window-fast.peg`. It uses ordinary PEG
nonterminals, sequence, ordered choice, predicates, repetition and literals.
The input is the original word over `{a,b}`. Its **79-case verification
passed**, with 29 accepted inputs and 50 rejected inputs, all agreeing with
the independent reversal oracle.

| Artifact | Rules | Bytes |
|---|---:|---:|
| Direct emission, `/tmp/pal-window-original.peg` | 59,169,304 | 2,118,673,778 |
| Inlined grammar, `/tmp/pal-window-fast.peg` | 13,248,052 | 672,208,000 |

The inlined file's SHA-256 is
`ab891da29e1959360f247e5b9b3f5d3dfec336da1f13376aa3e6b935bf3e211f`.
Inlining took 200.577 seconds. The raw grammar passed eight direct input
checks: epsilon, `a`, `b`, `aa`, `aba`, `abba` were accepted; `ab` and `abab`
were rejected. Its complete log is `/tmp/pal-window-raw-smoke.log`.

## Completed verification

The compact grammar was loaded once and run on all 63 binary words of
length at most five, 12 additional selected binary words through length 33,
and four words containing other symbols. Every report confirms `repeat=1`
and the original character count. The suite completed in 931.830 seconds,
including 167.053 seconds to load the grammar. Length 33 took 116.393 seconds.

The checked-in evidence is the [verification manifest](generated/window-pal-verification.json),
[all 79 reports](generated/window-pal-verification.log), and
[eight raw-grammar reports](generated/window-pal-raw-smoke.log). The manifest
identifies the exact grammar bytes by SHA-256. Rust's nine unit tests and
`sbt test` also passed; the latter reused the existing Scala test cache.

## Reproduce

From this directory:

```sh
python3 -u generate_window_pal.py /tmp/pal-window-original.peg --checkpoint /tmp/pal-window-original.sca
cargo build --offline --release --manifest-path rust-peg/Cargo.toml
rust-peg/target/release/compact-scaffold-peg /tmp/pal-window-original.peg /tmp/pal-window-fast.peg
python3 -u verify_window_pal.py /tmp/pal-window-fast.peg --runner rust-peg/target/release/plain-peg-runner --log /tmp/pal-window-fast-verify.log
```

The source checkpoint can resume emission with `generate_window_pal.py
OUTPUT --resume /tmp/pal-window-original.sca`. This checkpoint is compiler
data; the `.peg` file is the grammar that the independent runner reads.
The generator has no input-word or maximum-input-length argument.

## Why the construction has no input-length cutoff

`scaffold_window_pal.py` connects the two overlapping stages from
[DELAYED_PAL.md](DELAYED_PAL.md). Stage widths grow through persistent
pointer stacks; no finite table enumerates the permitted word lengths.
Each actual input character supplies one scaffold node. Windowed registers
and input heads retain unbounded historical pointers while computing a
fixed finite amount of intermediate work inside that transition.

The 512 matcher instructions and 1,024 flag instructions per worker and
arrival are derived in [GS_LOCAL_CLOCK.md](GS_LOCAL_CLOCK.md). They bound
work per character. The representations and normalization bounds are in
[WINDOW_ROUNDS.md](WINDOW_ROUNDS.md). The SCA-to-PEG translation reads input
in reverse; binary PAL is invariant under reversal.

Ordinary nonterminal inlining retains shared expressions and recursive
boundaries. Its depth 16 limits substitution in the grammar's syntax;
deeper references remain ordinary rules. Runtime matching uses neither
macro arguments nor callbacks nor repeated input characters.

The finite match suite checks the emitted artifact. It is separate from a
formal proof for every input length; the construction's loop accounting and
representation invariants remain available for that review.

## Independent re-check (2026-09-07, separate session)

The same inlined grammar (SHA-256 above) was rebuilt with `cargo build --release`
and run on inputs **not** in the 79-case suite:

* five words of length 6–7 — `aabbaa`, `abaaaba`, `aabbbaa` accepted; `aababa`,
  `baababb` rejected ([log](generated/window-pal-independent-check.log));
* four words of length 64–65, well beyond the longest verified case (33): a random
  even palindrome and its one-character corruption, `a^32 b a^32` and the same with the
  last character flipped — accepted / rejected / accepted / rejected
  ([log](generated/window-pal-long-check.log)).

Step counts grow roughly linearly with input length (487M at 33 characters, 834M–997M
at 64–65), as expected from a real-time machine simulation. The generator takes no
input-length bound; the `depth >= 16` limit in `compact_scaffold_peg.py` only stops
inlining and keeps the deeper rule as a nonterminal. The grammar's start rule
`S = baK5 ("a" / "b")* !.` and the 35,120+ `. X` (consume one character, then the rule
at the next position) occurrences in its first 300 MB are the TM-to-PEG encoding, not a
length-bounded unrolling.

All 647 Scala tests (36 suites, including the three new generated-grammar suites) pass
on a forced run without the sbt test cache.

**Closure check.** Because the inliner stops substituting at depth 16, the question
arose whether the emitted grammar could contain references to rules it never defines.
It cannot: the Rust runner resolves every reference at load time and rejects an undefined
name (`main.rs`, "undefined rule"), and a direct streaming count over the 672 MB file gives
13,248,052 defined rules, 13,248,051 referenced names (every rule except the start rule
`S`), **0 undefined references and 0 unreferenced definitions**. The rules left
unexpanded by the depth limit are ordinary nonterminals with definitions — they are
what the rule count measures.
