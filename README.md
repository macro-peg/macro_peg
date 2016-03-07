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

#### 0.0.2

* [Fix bug of HOPEGParser](https://github.com/kmizu/hopeg/commit/a7a72bcffd22401b9fec7a71ff2a5992e6fe7448)
* [Arithmetic HOPEG example](https://github.com/kmizu/hopeg/commit/1aadc5585490a13e6eb7cdbf60547eea1b424052)

### Usage (0.0.2)

Note that the behaviour could change.

Add the following lines to your build.sbt file:

```scala
libraryDependencies += ("com.github.kmizu" %% "hopeg" % "0.0.2")
```

Then, you can use `HOPEGParser` and `HOPEGEvaluator` as the followings:

```scala
import com.github.kmizu.hopeg._
val grammar = HOPEGParser.parse(
  """
        |S = P("") !.; P(r) = "a" P("a" r) / "b" P("b" r) / r;
  """.stripMargin
)
val evaluator = HOPEGEvaluator(grammar)
```

```scala
scala> val inputs = List(
     |   "a", "b", "aa", "bb", "ab", "ba", "aaa", "bbb", "aba", "bab", "abb", "baa", "aab", "bba",
     |   "aaaa", "bbbb", 
     |   "aaab", "aaba", "abaa", "baaa",
     |   "bbba", "bbab", "babb", "abbb",
     |   "aabb", "abba", "bbaa", "baab", "abab", "baba"
     | )
inputs: List[String] = List(a, b, aa, bb, ab, ba, aaa, bbb, aba, bab, abb, baa, aab, bba, aaaa, bbbb, aaab, aaba, abaa, baaa, bbba, bbab, babb, abbb, aabb, abba, bbaa, baab, abab, baba)

scala> inputs.map{input => s"${input} => ${evaluator.evaluate(input, 'S)}"}.mkString("\n")
res0: String =
a => None
b => None
aa => Some(aa)
bb => Some(bb)
ab => None
ba => None
aaa => None
bbb => None
aba => None
bab => None
abb => None
baa => None
aab => None
bba => None
aaaa => Some(aaaa)
bbbb => Some(bbbb)
aaab => None
aaba => None
abaa => None
baaa => None
bbba => None
bbab => None
babb => None
abbb => None
aabb => None
abba => Some(abba)
bbaa => None
baab => Some(baab)
abab => None
baba => None
```
