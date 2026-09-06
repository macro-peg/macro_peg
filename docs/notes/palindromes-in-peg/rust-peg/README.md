# Plain PEG runner

Reads a concrete `.peg` file and matches it with ordinary PEG semantics:
ordered choice, sequence, `!`, `&`, `*`, literals, `.`, and nonterminals.
Rules use `Name = expression;` syntax. The evaluator has no palindrome logic,
scaffold operations, callbacks, or grammar parameters.

```sh
cargo test --offline --manifest-path docs/notes/palindromes-in-peg/rust-peg/Cargo.toml
cargo build --offline --release --manifest-path docs/notes/palindromes-in-peg/rust-peg/Cargo.toml
docs/notes/palindromes-in-peg/rust-peg/target/release/plain-peg-runner example.peg '' a aa aba abba ab
```

Grammar expressions and nonterminals use integer IDs. An explicit evaluation
stack handles recursive grammars without recursive Rust calls. Packrat results
are memoized by nonterminal and input position; pages of up to 64 positions
are allocated on demand. Short inputs use a smaller power-of-two page, which
avoids allocating 64 entries for every queried rule when only a few positions
exist. A busy memo entry detects non-consuming recursion, and repetitions
must consume input on success.

Each match reports its Boolean result, elapsed time, interpreter step count,
memo payload estimate (not process RSS), and actual input character count.
The default passes the word unchanged. `--start NAME` selects a start rule.

`--repeat K` is only an explicit test-harness option for intermediate expanded
grammars: it repeats each supplied character K times **before** PEG evaluation.
Results using it are not evidence of a final grammar accepting unchanged input.
It is not a PEG operator and is not used by default.

The initial implementation was compared with the Python PEG evaluator on 428
cases across eight existing generated grammars, including ordered-choice,
predicate, repetition, tape-movement and marked-palindrome examples. Unit tests
also cover 10,000-character recursion, malformed rules and nullable cycles.

`compact-scaffold-peg SOURCE TARGET` reduces a concrete scaffold-generated
`S`/`B_n`/`P_n`/`E_n` grammar before loading it. It substitutes private
expressions, preserves predicate and choice scope, removes unreachable rules,
and shortens identifiers. Shared expressions and recursive label/pointer
boundaries remain nonterminals. Substitution stops after 16 levels, leaving
deeper references in the output; this does not limit accepted input length.
The pass reads and writes ordinary PEG syntax and never executes the source
machine or transforms input words. It requires one generated production per
line and writes through a temporary `.partial` file.

```sh
cargo run --offline --release --bin compact-scaffold-peg -- source.peg compact.peg
```

Its output on the 891,595-rule GS component is byte-identical to the Python
reference with `--short-names --inline-private`: 74,549 rules / 9,237,597 bytes.
The eight component traces also give the same answers in the ordinary runner.
