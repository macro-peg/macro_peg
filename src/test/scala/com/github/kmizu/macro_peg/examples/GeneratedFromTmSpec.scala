package com.github.kmizu.macro_peg.examples

import com.github.kmizu.macro_peg.{EvaluationResult, Interpreter}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

import scala.io.Source

/**
 * `docs/notes/palindromes-in-peg/generated/anbn_from_tm.peg` is a plain PEG *generated* from a
 * real-time one-tape Turing machine by `docs/notes/palindromes-in-peg/tm2peg.py`, using the
 * correspondence "PEG position i = the machine's configuration after reading reverse(w) up to
 * w(i)": each tape is a zipper whose stacks are chains of memo positions, so a head move is a
 * forward jump and the machine needs no position or identity comparison.
 *
 * This spec checks the generated grammar against the language it is supposed to define. It is the
 * pipeline that will produce a plain PEG for the full palindrome language once Galil's real-time
 * algorithm is written as such a machine.
 */
class GeneratedFromTmSpec extends AnyFunSpec with Diagrams {
  private val grammar: String = {
    val source = Source.fromFile("docs/notes/palindromes-in-peg/generated/anbn_from_tm.peg")
    try source.mkString finally source.close()
  }

  private lazy val accepts: String => Boolean = {
    val interpreter = Interpreter.fromSourceEither(grammar) match {
      case Right(value) => value
      case Left(diagnostic) => fail(s"generated grammar was rejected: $diagnostic")
    }
    input => interpreter.evaluate(input) == EvaluationResult.Success("")
  }

  private def isAnBn(input: String): Boolean = {
    val half = input.length / 2
    input.length % 2 == 0 && input == "a" * half + "b" * half
  }

  describe("a PEG compiled from a real-time Turing machine") {
    it("is accepted by the grammar parser and validator") {
      val parsed = Interpreter.fromSourceEither(grammar).isRight
      assert(parsed)
    }

    it("recognizes exactly a^n b^n on all binary strings up to length 6") {
      val corpus = {
        val all = scala.collection.mutable.ArrayBuffer("")
        var frontier = Seq("")
        for (_ <- 1 to 6) {
          frontier = frontier.flatMap(s => Seq(s + "a", s + "b"))
          all ++= frontier
        }
        all.toSeq
      }
      val wrong = corpus.filter(input => accepts(input) != isAnBn(input))
      assert(wrong.isEmpty, s"misclassified ${wrong.size}, e.g. ${wrong.take(5)}")
    }

    it("keeps counting beyond the exhaustive range") {
      assert(accepts("a" * 7 + "b" * 7))
      assert(!accepts("a" * 7 + "b" * 6))
      assert(!accepts("a" * 6 + "b" * 7))
    }
  }
}
