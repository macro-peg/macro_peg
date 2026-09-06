package com.github.kmizu.macro_peg.examples

import com.github.kmizu.macro_peg.{EvaluationResult, Interpreter}
import org.scalatest.funspec.AnyFunSpec

import scala.io.Source

class GeneratedScaffoldPegSpec extends AnyFunSpec {
  describe("ordinary PEG generated from symbolic scaffold equations") {
    it("recognizes marked palindromes using shared Boolean and pointer rules") {
      val source = Source.fromFile(
        "src/test/resources/palindromes/scaffold_marked_palindrome.peg")
      val grammar = try source.mkString finally source.close()
      val interpreter = Interpreter.fromSourceEither(grammar) match {
        case Right(value) => value
        case Left(diagnostic) => fail(s"generated grammar rejected: $diagnostic")
      }
      def accepts(input: String): Boolean =
        interpreter.evaluate(input) == EvaluationResult.Success("")
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
      val half = "abaabbab" * 8
      assert(accepts(half + "#" + half.reverse))
      assert(!accepts(half + "#" + half.reverse + "b"))
    }
  }
}
