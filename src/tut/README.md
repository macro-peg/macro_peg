## HOPEG: Higher Order Parsing Expression Grammar
 
[![Build Status](https://travis-ci.org/kmizu/hopeg.png?branch=master)](https://travis-ci.org/kmizu/hopeg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.kmizu/hopeg_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.kmizu/hopeg_2.11)
[![Scaladoc](http://javadoc-badge.appspot.com/com.github.kmizu/hopeg_2.11.svg?label=scaladoc)](http://javadoc-badge.appspot.com/com.github.kmizu/hopeg_2.11/index.html#com.github.kmizu.hopeg.package)
[![Reference Status](https://www.versioneye.com/java/com.github.kmizu:hopeg_2.11/reference_badge.svg?style=flat)](https://www.versioneye.com/java/com.github.kmizu:hopeg_2.11/references)

HOPEG is an extension of PEG by parametric rule (rule constructor).  It seems that expressiveness of HOPEG
is greather than (traditional) PEG since HOPEG can express palindromes.  This repository implements a HOPEG
interpreter (or matcher).

### Grammar of HOPEG in Pseudo PEG

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

#### 0.0.3
* [Fix bug of HOPEGEvaluator](https://github.com/kmizu/hopeg/commit/86b7c43ef52b9a6d2e81fcb541aca93e89b276ae)
* [Modifier HOPEG example](https://github.com/kmizu/hopeg/commit/00221379bec06ddf3392e50803f6bf5d1316b579)

#### 0.0.2

* [Fix bug of HOPEGParser](https://github.com/kmizu/hopeg/commit/a7a72bcffd22401b9fec7a71ff2a5992e6fe7448)
* [Arithmetic HOPEG example](https://github.com/kmizu/hopeg/commit/1aadc5585490a13e6eb7cdbf60547eea1b424052)

### Usage

Note that the behaviour could change.

Add the following lines to your build.sbt file:

```scala
libraryDependencies += ("com.github.kmizu" %% "hopeg" % "0.0.3")
```

Then, you can use `HOPEGParser` and `HOPEGEvaluator` as the followings:

```tut:silent
import com.github.kmizu.hopeg._
val grammar = HOPEGParser.parse(
  """
        |S = P("") !.; P(r) = "a" P("a" r) / "b" P("b" r) / r;
  """.stripMargin
)
val evaluator = HOPEGEvaluator(grammar)
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
