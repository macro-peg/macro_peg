## HOPEG: Higher Order Parsing Expression Grammar [![Build Status](https://travis-ci.org/kmizu/hopeg.png?branch=master)](https://travis-ci.org/kmizu/hopeg)

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
    
### Usage (0.0.1-SNAPSHOT)

Note that the behaviour could change.

Add the following lines to your build.sbt file:

```scala
resolvers += ("snapshots" at "http://oss.sonatype.org/content/repositories/snapshots")

libraryDependencies += ("com.github.kmizu" %% "hopeg" % "0.0.1-SNAPSHOT")
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
