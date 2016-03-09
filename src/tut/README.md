## Macro PEG: PEG with macro-like rules
 
[![Build Status](https://travis-ci.org/kmizu/macro_peg.png?branch=master)](https://travis-ci.org/kmizu/hopeg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.kmizu/macro_peg_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.kmizu/macro_peg_2.11)
[![Scaladoc](http://javadoc-badge.appspot.com/com.github.kmizu/macro_peg_2.11.svg?label=scaladoc)](http://javadoc-badge.appspot.com/com.github.kmizu/macro_peg_2.11/index.html#com.github.kmizu.macro_peg.package)
[![Reference Status](https://www.versioneye.com/java/com.github.kmizu:macro_peg_2.11/reference_badge.svg?style=flat)](https://www.versioneye.com/java/com.github.kmizu:macro_peg_2.11/references)

Macro PEG is an extended PEG by macro-like rules.  It seems that expressiveness of Macro PEG
is greather than traditional PEG since Macro PEG can express palindromes.  This repository implements a Macro PEG
interpreter (or matcher).

### Grammar of Macro PEG in Pseudo PEG

Note that spacing is eliminated.

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
             / Identifier
             / StringLiteral;
    
    StringLiteral <- "\\" (!"\\" .) "\\";
    
    Call <- Identifier "(" Expression ("," Expression)* ")";
    
    Identifier <- [a-zA-Z_] ([a-zA-Z0-9_])*;
    
### Release Note

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
libraryDependencies += ("com.github.kmizu" %% "macro_peg" % "0.0.5")
```

Then, you can use `MacroPEGParser` and `MacroPEGEvaluator` as the followings:

```tut:silent
import com.github.kmizu.macro_peg._
val grammar = MacroPEGParser.parse(
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
