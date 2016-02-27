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
    
### Usage

Note that the behaviour could change.

Add the following lines to your build.sbt file (Snaphot version):

```scala
resolvers += ("snapshots" at "http://oss.sonatype.org/content/repositories/snapshots")

libraryDependencies += ("com.github.kmizu" %% "hopeg" % "0.0.2-SNAPSHOT")
```

```scala
libraryDependencies += ("com.github.kmizu" %% "hopeg" % "0.0.1")
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
