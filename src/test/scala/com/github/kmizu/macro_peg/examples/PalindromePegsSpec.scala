package com.github.kmizu.macro_peg.examples

import com.github.kmizu.macro_peg.{EvaluationResult, Interpreter}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

/**
 * Every grammar produced by [[PalindromePegs]] is checked exhaustively: for all binary strings up
 * to [[exhaustiveLength]] the grammar must accept exactly the members of the intended language,
 * so the examples are witnesses, not sketches.
 */
class PalindromePegsSpec extends AnyFunSpec with Diagrams {
  private val exhaustiveLength = 10
  private val deepLength = 13

  private def stringsUpTo(maxLength: Int): Seq[String] = {
    val all = scala.collection.mutable.ArrayBuffer("")
    var frontier = Seq("")
    for (_ <- 1 to maxLength) {
      frontier = frontier.flatMap(s => Seq(s + "a", s + "b"))
      all ++= frontier
    }
    all.toSeq
  }

  private val corpus: Seq[String] = stringsUpTo(exhaustiveLength)

  private def recognizer(source: String): String => Boolean = {
    val interpreter = Interpreter.fromSourceEither(source) match {
      case Right(value) => value
      case Left(diagnostic) => fail(s"grammar was rejected: $diagnostic\n$source")
    }
    input => interpreter.evaluate(input) == EvaluationResult.Success("")
  }

  private def assertLanguage(
    source: String,
    expected: String => Boolean,
    inputs: Seq[String] = corpus
  ): Unit = {
    val accepts = recognizer(source)
    val wrong = inputs.filter(input => accepts(input) != expected(input))
    assert(
      wrong.isEmpty,
      s"misclassified ${wrong.size} inputs, e.g. ${wrong.take(5).map(s => s"\"$s\"").mkString(", ")}\n$source"
    )
  }

  private def isPalindrome(input: String): Boolean = input == input.reverse

  private def countB(input: String): Int = input.count(_ == 'b')

  private def mirrorsToDepth(input: String, depth: Int): Boolean =
    (0 until depth).forall { k =>
      2 * k + 1 >= input.length || input(k) == input(input.length - 1 - k)
    }

  describe("Macro PEG") {
    it("recognizes exactly the palindromes, using the macro parameter as the reversed prefix") {
      assertLanguage(PalindromePegs.macroPalindrome, isPalindrome, stringsUpTo(deepLength))
    }

    it("recognizes exactly the even-length palindromes without the odd centre (LMR Conjecture 7)") {
      assertLanguage(
        PalindromePegs.macroEvenPalindrome,
        input => isPalindrome(input) && input.length % 2 == 0,
        stringsUpTo(deepLength)
      )
    }

    it("depends on the order of the two closing alternatives: even-first rejects \"aaa\"") {
      val accepts = recognizer("""S = P("") !.; P(r) = "a" P("a" r) / "b" P("b" r) / r / [ab] r;""")
      assert(accepts("abba"))
      assert(!accepts("aaa"))
    }
  }

  describe("inner family (plain PEG): palindromes with a bounded number of 'b'") {
    for (k <- 0 to 6) {
      it(s"innerExact($k) is exactly the palindromes with exactly $k occurrences of 'b'") {
        assertLanguage(
          PalindromePegs.innerExact(k),
          input => isPalindrome(input) && countB(input) == k
        )
      }
    }

    for (maxB <- 0 to 6) {
      it(s"inner($maxB) is exactly the palindromes with at most $maxB occurrences of 'b'") {
        assertLanguage(
          PalindromePegs.inner(maxB),
          input => isPalindrome(input) && countB(input) <= maxB
        )
      }
    }

    it("the inner chain increases and exhausts PAL") {
      val members = (0 to 6).map(maxB => corpus.filter(recognizer(PalindromePegs.inner(maxB))))
      for (maxB <- 0 until 6) assert(members(maxB).toSet.subsetOf(members(maxB + 1).toSet))
      val palindromes = corpus.filter(isPalindrome).filter(input => countB(input) <= 6)
      assert(palindromes.toSet.subsetOf(members(6).toSet))
    }
  }

  describe("outer family (plain PEG): the macro parameter unrolled to depth K") {
    for (depth <- 0 to 6) {
      it(s"outer($depth) is exactly the strings whose first $depth characters mirror the last $depth") {
        assertLanguage(
          PalindromePegs.outer(depth),
          input => mirrorsToDepth(input, depth)
        )
      }
    }

    it("the outer chain decreases and closes down on PAL") {
      val members = (0 to 6).map(depth => corpus.filter(recognizer(PalindromePegs.outer(depth))))
      for (depth <- 0 until 6) assert(members(depth + 1).toSet.subsetOf(members(depth).toSet))
      // every palindrome survives every level, and by depth 6 nothing shorter than 13 survives but palindromes
      assert(corpus.filter(isPalindrome).toSet.subsetOf(members(6).toSet))
      assert(members(6).forall(isPalindrome))
    }
  }

  describe("bounded slices (plain PEG): PAL restricted to a maximum length") {
    for (maxLength <- 0 to exhaustiveLength) {
      it(s"bounded($maxLength) is exactly the palindromes of length at most $maxLength") {
        assertLanguage(
          PalindromePegs.bounded(maxLength),
          input => isPalindrome(input) && input.length <= maxLength
        )
      }
    }
  }

  describe("why the families are needed") {
    it("the textbook recursive grammar is not a PEG for PAL: it rejects \"aaaa\"") {
      val accepts = recognizer("""S = P !.; P = "a" P "a" / "b" P "b" / "a" / "b" / "";""")
      assert(accepts("aba"))
      assert(!accepts("aaaa"))
      assert(!accepts("abba"))
    }
  }
}
