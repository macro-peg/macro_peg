## Macro PEG: PEG with macro-like rules
 
[![Gitter](https://badges.gitter.im/kmizu/macro_peg.svg)](https://gitter.im/kmizu/macro_peg?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)
[![Build Status](https://travis-ci.org/kmizu/macro_peg.png?branch=master)](https://travis-ci.org/kmizu/macro_peg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.kmizu/macro_peg_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.kmizu/macro_peg_2.11)
[![Scaladoc](http://javadoc-badge.appspot.com/com.github.kmizu/macro_peg_2.11.svg?label=scaladoc)](http://javadoc-badge.appspot.com/com.github.kmizu/macro_peg_2.11/index.html#com.github.kmizu.macro_peg.package)
[![Reference Status](https://www.versioneye.com/java/com.github.kmizu:macro_peg_2.11/reference_badge.svg?style=flat)](https://www.versioneye.com/java/com.github.kmizu:macro_peg_2.11/references)

Macro PEG is an extended PEG by macro-like rules.  It seems that expressiveness of Macro PEG
is greather than traditional PEG since Macro PEG can express palindromes.  This repository implements a Macro PEG
interpreter (or matcher).

### Grammar of Macro PEG in Pseudo PEG

Note that spacing is ommited.

    Grammer <- Rule* ";";
    
    Rule <- Identifier ("(" Identifier ("," Identifer)* ")")? "=" Expression ";";
    
    Expression <- Sequence ("/" Sequence)*;
    
    Sequence <- Prefix+;
    
    Prefix <-  ("&" / "!") Suffix;
    
    Suffix <- Primary "+"
            /  Primary "*"
            /  Primary "?"
            /  Primary;
    
    Primary <- "(" Expression ")"
             /  Call
             / Debug
             / Identifier
             / StringLiteral
             / CharacterClass;
             
    Debug <- "Debug" "(" Expression ")";
    
    StringLiteral <- "\\" (!"\\" .) "\\";
    
    Call <- Identifier "(" Expression ("," Expression)* ")";
    
    Identifier <- [a-zA-Z_] ([a-zA-Z0-9_])*;
    
    CharacterClass <- "[" "^"? (!"[" .)+ "]"
    
### Release Note

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

### Usage

Note that the behaviour could change.

Add the following lines to your build.sbt file:

```scala
libraryDependencies += ("com.github.kmizu" %% "macro_peg" % "0.0.9")
```

Then, you can use `MacroPEGParser` and `MacroPEGEvaluator` as the followings:

```tut:silent
import com.github.kmizu.macro_peg._
import MacroPEGRunner._
import MacroPEGParser._
val grammar = parse(
  """
        |S = P("") !.; P(r) = "a" P("a" r) / "b" P("b" r) / r;
  """.stripMargin
)
val evaluator = MacroPEGEvaluator(grammar)
```

```tut
val inputs = List(
  "a", "b", "aa", "bb", "ab", "ba", "aaa", "bbb", "aba", "bab", "abb", "baa", "aab", "bba",
  "aaaa", "bbbb", 
  "aaab", "aaba", "abaa", "baaa",
  "bbba", "bbab", "babb", "abbb",
  "aabb", "abba", "bbaa", "baab", "abab", "baba"
)
inputs.map{input => s"${input} => ${evaluator.evaluate(input, 'S)}"}.mkString("\n")
```

```tut
tryGrammar(
      "modifiers",
      """
    |S = Modifiers(!"", "") !.;
    |Modifiers(AlreadyLooked, Scope) = (!AlreadyLooked) (
    |    &(Scope) Token("public") Modifiers(AlreadyLooked / "public", "public")
    |  / &(Scope) Token("protected") Modifiers(AlreadyLooked / "protected", "protected")
    |  / &(Scope) Token("private") Modifiers(AlreadyLooked / "private", "private")
    |  / Token("static") Modifiers(AlreadyLooked / "static", Scope)
    |  / Token("final") Modifiers(AlreadyLooked / "final", Scope)
    |  / ""
    |);
    |Token(t) = t Spacing;
    |Spacing = " "*;
    """.stripMargin, "public static final", "public public", "public static public", "final static public", "final final", "public private", "protected public", "public static")
```

```tut
tryGrammar(
      "subtract",
        """
      |S = ReadRight("") !.;
      |// the number of occurence of '1 represents a natural number.
      |// a-b=c
      |// Essentially, this checks a=b+c.
      |ReadRight(Right)
      |  = &("1"* "-" Right "1") ReadRight(Right "1")
      |  / &("1"* "-" Right "=") ReadDiff(Right, "");
      |
      |ReadDiff(Right, Diff)
      |  = &("1"* "-" Right "=" Diff "1") ReadDiff(Right, Diff "1")
      |  / &("1"* "-" Right "=" Diff !.) Check(Right, Diff);
      |
      |Check(Right, Diff)
      |  = Right Diff "-" Right "=" Diff;
      """.stripMargin,
      "11-1=1", "1-1=", "111-11=1", // should match
      "111-1=1",  "111-1=111", "1-11=" // should not match
    )
```

```tut
    tryGrammar(
      "exponent",
        """
      |S = ReadLeft("", "") !.;
      |// the number of occurence of '1 represents a natural number.
      |// |Seq| is the length of a sequence Seq.
      |// ^ is exponent operator
      |// ReadLeft("", "") checks input is a correct expression a^b=c.
      |
      |// Read a.
      |// LeftAsOnes is a sequence of "1" where |LeftAsOnes| = |a|.
      |// LeftAsDots is a sequence of . where |LeftAsDots| = |a|.
      |ReadLeft(LeftAsOnes, LeftAsDots)
      |  = &(LeftAsOnes "1") ReadLeft(LeftAsOnes "1", LeftAsDots .)
      |  / &(LeftAsOnes "^") ComputePadding(LeftAsOnes, LeftAsDots, "");
      |
      |// Compute Padding which is a sequene of .
      |// where |Padding| + |LeftAsDots| = |Input|
      |ComputePadding(LeftAsOnes, LeftAsDots, Padding)
      |  = &(Padding LeftAsDots .) ComputePadding(LeftAsOnes, LeftAsDots, Padding .)
      |  / &(Padding LeftAsDots !.) ReadRight(LeftAsOnes, Padding, "", "1");
      |
      |// Read b.
      |// Exp = a^Right.
      |ReadRight(Left, Padding, Right, Exp)
      |  = &(Left "^" Right "1") Multiply(Left, Padding, Right "1", Exp, "", "")
      |  / &(Left "^" Right "=") Check(Left, Right, Exp);
      |
      |// Compute Left * OldExp.
      |// This adds OldExp Left times into Exp.
      |// I is a loop counter.
      |Multiply(Left, Padding, Right, OldExp, Exp, I)
      |  = &(Padding I .) Multiply(Left, Padding, Right, OldExp, Exp OldExp, I .)
      |  / &(Padding I !.) ReadRight(Left, Padding, Right, Exp);
      |
      |// Check whole input.
      |Check(Left, Right, Exp)
      |  = Left "^" Right "=" Exp;
      """.stripMargin,
      "11^111=11111111", "11^=1", "1^11=1", "^11=", // should match
      "11^111=1111111",  "11^111=111111111" // should not match
    )
```

```tut
    tryGrammar(
      "identifier",
      """S = [a-zA-Z_][a-zA-Z0-9_]*;""",
      "hoge", "foo", "hoge1", "foo1", "1foo", "2hoge", "123"
    )
```
