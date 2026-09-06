package com.github.kmizu.macro_peg.examples

import com.github.kmizu.macro_peg.{EvaluationResult, Interpreter}
import org.scalatest.funspec.AnyFunSpec

import scala.io.Source

class SparseGeneratedFromTmSpec extends AnyFunSpec {
  private def recognizer(file: String): String => Boolean = {
    val source = Source.fromFile(s"docs/notes/palindromes-in-peg/generated/$file")
    val grammar = try source.mkString finally source.close()
    val interpreter = Interpreter.fromSourceEither(grammar) match {
      case Right(value) => value
      case Left(diagnostic) => fail(s"generated grammar rejected: $diagnostic")
    }
    input => interpreter.evaluate(input) == EvaluationResult.Success("")
  }

  describe("plain PEG output from sparse multi-tape transition guards") {
    it("recognizes marked palindromes with nineteen tapes without enumerating focus vectors") {
      val accepts = recognizer("sparse_marked_palindrome.peg")
      def expected(input: String): Boolean = {
        val marker = input.indexOf('#')
        marker >= 0 && marker == input.lastIndexOf('#') &&
          input.take(marker) == input.drop(marker + 1).reverse
      }
      var words = Seq("")
      for (_ <- 0 to 6) {
        words.foreach(input => assert(accepts(input) == expected(input), input))
        words = words.flatMap(word => Seq(word + "a", word + "b", word + "#"))
      }
      val left = "aababbbabaa" * 3
      assert(accepts(left + "#" + left.reverse))
      assert(!accepts(left + "#" + left.reverse + "a"))
    }

    it("preserves a focus symbol on a move with no explicit write") {
      val accepts = recognizer("sparse_preserving_move.peg")
      for (n <- 0 to 8) assert(accepts("a" * n) == (n == 4), s"length $n")
    }

    it("uses supported escapes for backspace input") {
      val accepts = recognizer("sparse_backspace.peg")
      assert(accepts(""))
      assert(accepts("\b\b"))
      assert(!accepts("b"))
    }
  }
}
