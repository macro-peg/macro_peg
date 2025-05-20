# Macro PEG

Macro PEG extends Parsing Expression Grammars with macro-like rules and is implemented in Scala 3. It supports lambda-style macros so you can build higher-order grammars.

## Grammar Overview

Whitespace is omitted in the grammar below.

```
Grammar       <- Definition* ";"
Definition    <- Identifier ("(" Arg ("," Arg)* ")")? "=" Expression ";"
Arg           <- Identifier (":" Type)?
Type          <- RuleType / "?"
RuleType      <- ("(" Type ("," Type)* ")" "->" Type)
               / (Type "->" Type)
Expression    <- Sequence ("/" Sequence)*
Sequence      <- Prefix+
Prefix        <- ("&" / "!") Suffix
               / Suffix
Suffix        <- Primary "?"
               / Primary "*"
               / Primary "+"
               / Primary
Primary       <- "(" Expression ")"
               / Call
               / Debug
               / Identifier
               / StringLiteral
               / CharacterClass
               / Lambda
Call          <- Identifier "(" Expression ("," Expression)* ")"
Debug         <- "Debug" "(" Expression ")"
Lambda        <- "(" Identifier ("," Identifier)* "->" Expression ")"
StringLiteral <- '"' (!'"' .)* '"'
CharacterClass<- '[' '^'? (!']' .)+ ']'
```

## Features

- Macro rules with parameters
- Lambda macros for higher-order grammars
- Type annotations for macro parameters
- Multiple evaluation strategies (call by name, call by value sequential/parallel)
- Parser combinator library `MacroParsers`
- Debug expressions for inspecting matches

## Getting Started

Add the library to your `build.sbt`:

```scala
libraryDependencies += "com.github.kmizu" %% "macro_peg" % "0.1.1-SNAPSHOT"
```

Then parse and evaluate a grammar:

```scala
import com.github.kmizu.macro_peg._

val grammar = Parser.parse("""
  S = Double((x -> x x), "aa") !.;
  Double(f: ?, s: ?) = f(f(s));
""")

val evaluator = Evaluator(grammar)
val result = evaluator.evaluate("aaaaaaaa", Symbol("S"))
println(result)
```

## Release Note

#### 0.0.9

* [Generalize evalCC combinator.](https://github.com/kmizu/macro_peg/commit/fd4525f86d30b3dd6573f33deebdc5f00a08c9a3)
* ['undefined variable is parse error' example.](https://github.com/kmizu/macro_peg/commit/bbfc7b1bd9c4fdabac9a45050cbe1416ff09db7e)
* [Fix bug of + combinator.](https://github.com/kmizu/macro_peg/commit/187e4489d062286a66c6dc39c73bd559543d746c)

#### 0.0.8
* [Introduce backreference as `evalCC` method.](https://github.com/kmizu/macro_peg/commit/91154c8da2148f38434bb91b292b202429d21de1)
* [pfun -> delayedParser, which is better naming than before(breaking change)](https://github.com/kmizu/macro_peg/commit/e5195caaa0248e8a05233326081de0296ce3dc26)

#### 0.0.7
* [Introduce MacroParsers, parser combinator library for Macro PEG.](https://github.com/kmizu/macro_peg/commit/3866502bf699ff6aac2426fc21a9fa6e97c00d09)
  * See [tests](https://github.com/kmizu/macro_peg/blob/3866502bf699ff6aac2426fc21a9fa6e97c00d09/src/test/scala/com/github/kmizu/macro_peg/MacroParsersSpec.scala)

#### 0.0.6
* [More accurate ParseException](https://github.com/kmizu/macro_peg/commit/40f9c1198127307f6f8e7f151c2fa216b0d6dca0)
* [EvaluationException is thrown when arity of function is not equal to passed params.](https://github.com/kmizu/macro_peg/commit/cceaf815debc5995d88b8019479bf156e0047016)
* [Improved Parser](https://github.com/kmizu/macro_peg/commit/4038366c73086c7ef50384e39db6e004f88b34cd)

#### 0.0.5
* [subtract and exponent example](https://github.com/kmizu/macro_peg/commit/c6fcd9c77174400c5629cc3ee5f243ff174f5ea2)
* [Character class support!](https://github.com/kmizu/macro_peg/commit/753dbca88a64bbba1dd57e4f9ca29e763f25c570)
* [| is now alias of /](https://github.com/kmizu/macro_peg/commit/11bc7037d5699f9fb4a168aa9801f3f6251f8c8a)
* [Modifiers + Scope](https://github.com/kmizu/macro_peg/commit/2d523000782ea0b9b3deca3d54cc7fb3cded7657)

#### 0.0.4
* [HOPEG is now Macro PEG](https://github.com/kmizu/macro_peg/commit/8bd5129ccb6268d09b72ef7460e16b873f0fc3f3)
* [Debug expression is implemented](https://github.com/kmizu/macro_peg/commit/d013760105974a8446da023147f0cade10679c8a)

#### 0.0.3
* [Fix bug of MacroPEGEvaluator](https://github.com/kmizu/macro_peg/commit/86b7c43ef52b9a6d2e81fcb541aca93e89b276ae)
* [Modifier HOPEG example](https://github.com/kmizu/macro_peg/commit/00221379bec06ddf3392e50803f6bf5d1316b579)

#### 0.0.2

* [Fix bug of HOPEGParser](https://github.com/kmizu/macro_peg/commit/a7a72bcffd22401b9fec7a71ff2a5992e6fe7448)
* [Arithmetic HOPEG example](https://github.com/kmizu/macro_peg/commit/1aadc5585490a13e6eb7cdbf60547eea1b424052)

## Running Tests

Execute the following command:

```bash
sbt test
```

## License

This project is released under the MIT License.
