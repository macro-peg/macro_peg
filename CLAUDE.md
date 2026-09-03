# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Macro PEG** is a Scala 3 implementation of Parsing Expression Grammars extended with higher-order lambda-style macros. Key innovation: parameterized grammar rules like `Double(f: ?, s: ?) = f(f(s))` enable composable, higher-order parsers. The flagship demo is a complete Ruby 3.x parser (`RubyParser`) achieving 100% success on the upstream Ruby test corpus (301/301 files at 5s timeout). Note: the `ParserGenerator` (Scala source code generation) has not yet achieved 100% Ruby coverage — it falls back to the interpreter for higher-order rules but full Ruby parity is still in progress.

## Build & Test Commands

```bash
# Compile
sbt compile

# Run all tests. In sbt 2 the plain `test` task is INCREMENTAL (it is an alias for
# `testQuick`: only tests that failed before, never ran, or whose dependencies changed).
# `testFull` is the one that always runs everything — use it in CI and before a release.
sbt testFull

# Run a single test suite
sbt "testOnly com.github.kmizu.macro_peg.ParserSpec"
sbt "testOnly com.github.kmizu.macro_peg.ruby.RubyParserSpec"

# Auto-rerun on changes
sbt ~test

# Run Ruby corpus runner
sbt "runMain com.github.kmizu.macro_peg.ruby.RubyCorpusRunner"
```

**Scala version:** 3.3.7 | **Build tool:** SBT | **Key dependency:** `scomb` (parser combinators)

### Ruby Corpus Environment Variables

```bash
RUBY_CORPUS_TIMEOUT_MS=5000     # Per-file timeout (default: 5000)
RUBY_CORPUS_FAIL_SAMPLES=20     # Max failure samples shown
RUBY_CORPUS_FULL_ERROR=1        # Full formatted output
RUBY_CORPUS_FAIL_OUT=out.tsv    # TSV file for results
RUBY_CORPUS_CLUSTER=true        # Group failures by error type
RUBY_CORPUS_PROFILE=true        # Show slowest files
```

### Corpus Setup (one-time)

```bash
mkdir -p third_party/ruby3/upstream
git clone --depth 1 --filter=blob:none --sparse https://github.com/ruby/ruby.git third_party/ruby3/upstream/ruby
cd third_party/ruby3/upstream/ruby
git sparse-checkout set test/ruby bootstraptest test/prism
```

## Architecture

All source lives under `src/main/scala/com/github/kmizu/macro_peg/` and `src/test/scala/com/github/kmizu/macro_peg/`.

### Core Pipeline

Parsing a grammar string goes through these stages in order:

1. **`Parser.scala`** — Parses Macro PEG grammar syntax into AST using `scomb`
2. **`GrammarValidator.scala`** — Detects undefined references, nullable repetitions, left recursion
3. **`TypeChecker.scala`** — Infers and validates macro parameter types
4. **`Evaluator.scala`** — Evaluates rules against input with packrat-style memoization
5. **`Interpreter.scala`** — High-level API orchestrating all of the above; returns `Either[Diagnostic, Result]`

### Key Modules

| File | Role |
|------|------|
| `Ast.scala` | All AST node types: `Sequence`, `Alternation`, `Call`, `Function`, `Grammar`, `Rule` |
| `Evaluator.scala` | Core engine; supports 3 strategies: `CallByName`, `CallByValueSeq`, `CallByValuePar` |
| `Diagnostic.scala` | Rich error reporting with phase, position, rule stack, snippet, and hint |
| `InlineMacroParsers.scala` | Compile-time grammar validation via Scala 3 macros |
| `combinator/MacroParsers.scala` | Ergonomic parser combinator library; key combinators: `cut`, `guard`, `label`, `recover` |
| `codegen/ParserGenerator.scala` | Generates Scala source from first-order grammars; falls back to `Interpreter` for higher-order rules. **Ruby 100% not yet achieved via this path.** |
| `ruby/RubyParser.scala` | Full Ruby 3.x parser (~105KB) built on `MacroParsers` |
| `ruby/RubyAst.scala` | Ruby AST node definitions |
| `ruby/RubyCorpusRunner.scala` | Batch runner against Ruby upstream test files |

### Diagnostic Phases

Errors are tagged with the phase they occur in: `Parse`, `WellFormedness`, `TypeCheck`, `Evaluation`, `Generation`. Always prefer returning `Either[Diagnostic, T]` over throwing exceptions in new code.

### `guard(name)(parser)` — Critical Combinator

The `guard` combinator in `MacroParsers` detects non-consuming recursion (rules that match zero characters) to prevent infinite loops. It replaces the need for manual left-recursion detection in the combinator-based Ruby parser. Use it when defining recursive rules that could potentially consume nothing.

## Code Style

- 2-space indentation (Scala convention)
- When adding new evaluation behavior, extend `Evaluator.scala` and add corresponding tests in `EvaluatorSpec.scala`
- New grammar features need: AST node in `Ast.scala` → parser case in `Parser.scala` → evaluator case in `Evaluator.scala` → type checker case in `TypeChecker.scala`
