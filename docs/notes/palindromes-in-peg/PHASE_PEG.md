# Removing fixed virtual input expansion

`phase_peg.inverse_repeat(source, k)` is an experimental compiler for the
ordinary PEG subset emitted by `symbolic_tm2peg.py`. Its intended contract is

```
G_k accepts w  iff  G accepts expand_k(w)
expand_k(c1 ... cn) = c1^k ... cn^k
```

The output is a finite ordinary PEG. Input remains unchanged: no separator,
padding, macro, semantic action or host callback is used. This closes a
specific output-boundary gap, not the complete binary PAL construction.

## Finite phase construction

A position in the virtual string is represented by `(p, i)`, where `p` is
the real input position and `0 <= i < k` is a virtual offset in its repeated
character. Only `(n, 0)` is a reachable EOF position. The phase is compiled
into rule names; it is not a mutable runtime value.

For each source expression E, emit E_i_j. A success means that evaluating E
from virtual phase i ends in phase j, and the consumed real input identifies
the returned real position. At phases below k-1, a virtual character match is
a lookahead on the current real character. At phase k-1 it consumes that
character and returns phase zero. Literal strings are first split into
characters. Empty expressions preserve phase.

Y_E_i tests whether E succeeds in any return phase. A sequence E F enumerates
intermediate phases m with E_i_m F_m_j. A source PEG has at most one successful
return position, hence at most one valid intermediate phase; later failure
cannot choose a different successful parse of E.

For ordered choice E / F, the general translation is

```
E_i_j / (!Y_E_i F_i_j)
```

The negative guard is essential: E may succeed in a different phase from j,
in which case source choice is already committed and F must not be tried.
When a sound static analysis proves E has no other return phase, the guard
can be omitted because ordinary choice already enforces that commitment.

Predicates keep phase and test Y. Repetition is recursive E followed by the
repetition, with an empty termination branch guarded by !Y_E_i. It must not
stop just because the greedy result returns in a different phase. For
example, `S = ("a" &"a")* "aa" !.;` under k=2 must reject input `a`.

The correctness argument is induction on a terminating source evaluation:
terminals implement the virtual position map; the unique source result
selects each sequence phase; the success guards preserve source choice,
predicates and repetition. Rule references use the same invariant. Recursive
calls along a terminating source execution therefore follow its finite
evaluation derivation, including virtual moves within one real character.
This is a written argument plus differential evidence, not mechanized proof.

## Static phase pruning and executable scope

The compiler computes a monotone may-return relation over the finite set of
phase pairs for each expression. Sequence composes relations, choice unions
them, predicates keep phase, and repetition takes reflexive transitive
closure. Negative predicates conservatively allow their diagonal. The least
fixed point overapproximates every terminating success. Impossible relation
branches are removed before rendering.

This also matters operationally: the initial unpruned output retained paths
starting with guaranteed failure. The Scala validator conservatively treats
negative predicates as nullable and spent excessive time traversing those
paths. A stack trace identified its repeated left-recursion search; that
experimental run was stopped. Pruning solved the observed cases without
changing or bypassing validation. It is not a completeness claim for that
validator on every operationally terminating PEG.

The supported syntax is double-quoted JSON-style literals, `.`, identifiers,
sequence, `/`, `!`, `&`, parentheses and `*`. The default start rule is `S`, matching Interpreter; `start=` / `--start`
selects another rule explicitly. Source grammars must terminate;
nullable repetition and non-consuming recursion are not given new semantics.
Input and literals are BMP scalar text so Python and Scala UTF-16 positions
agree. Width is a positive integer. No full Macro PEG parser is claimed.

The expression construction uses O(N k^2) named relations and up to
O(N k^3) sequence/repetition alternatives, before simplification. Large
scheduling constants may still produce impractically large grammars.

## Connection to bounded TM microsteps

Given a finite TM that takes exactly k local transitions per input character,
store its phase and current input character in finite control. It can be
viewed as a one-transition machine on expand_k(reverse(w)); each virtual
copy supplies the same character. Unused transitions in an at-most-k routine
can be idle transitions. Acceptance is sampled at completed blocks.

The sparse TM compiler already recognizes the reversal of a machine's input
language. Uniform expansion commutes with reversal, so applying inverse
expansion to that generated PEG yields the desired language on unchanged w.
This assumes the finite machine and its fixed instruction bound actually
exist. It does not turn an offline linear-time routine into such a machine.

The tape regression starts blank, writes x, moves right preserving the
focus, moves left preserving the focus, and tests x. It accepts exactly four
virtual `a` characters. The k=2 PEG accepts `aa`; k=4 accepts `a`, requiring
a push and pop within the same real input character. Both run through the
actual Scala Parser, GrammarValidator and Interpreter.

## Evidence and reproduction

- Python unit suite: all 43 tests passed, including six phase tests.
- Independent review exercised 40,320 random acyclic cases and 1,260
  recursive/predicate-recursive comparisons at widths 1 through 5 on the
  initial construction. Another 25,200 comparisons after pruning passed.
  Final re-review reported no remaining findings; the start-rule issue was
  fixed with a RED-to-GREEN regression.
- Real Scala phase suite: three tests passed. Besides the two tape fixtures,
  it checks priority commitment and all 127 binary strings of length <=6
  against balanced recursion, `a*b`, and a greedy-repetition rejection oracle.
- `sbt test` selected those three new tests and passed. The subsequent
  explicit full Scala run passed all 646 tests across 37 suites in 65 seconds
  (`/tmp/macro-peg-phase-all.log`).

Regenerate all six examples:

```
python docs/notes/palindromes-in-peg/generate_phase_examples.py
python -m unittest discover -s docs/notes/palindromes-in-peg -p test_phase_peg.py
XDG_RUNTIME_DIR=/tmp/macro-peg-pal-runtime sbt --server --batch 'testOnly *PhaseGeneratedPegSpec'
```

The move fixtures contain 457 rules / 10,630 bytes at k=2 and 1,521 rules /
36,826 bytes at k=4. The CLI also accepts `source.peg width output.peg [--start RULE]`.

Still missing for PAL: globally paced Galil matching/search stages, actual
center shifts and restarts, nonchain move/main1 replay, input storage and
multihead handling, and a real local-instruction bound for the resulting
complete recognizer. Existing stage3 generator-yield budgets remain
insufficient evidence for that bound.
