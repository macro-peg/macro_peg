package com.github.kmizu.macro_peg.examples

import com.github.kmizu.macro_peg.{EvaluationResult, Interpreter}
import org.scalatest.funspec.AnyFunSpec

import scala.io.Source

class PhaseGeneratedPegSpec extends AnyFunSpec {
  private def recognizer(file: String): String => Boolean = {
    val source = Source.fromFile(s"docs/notes/palindromes-in-peg/generated/$file")
    val grammar = try source.mkString finally source.close()
    val interpreter = Interpreter.fromSourceEither(grammar) match {
      case Right(value) => value
      case Left(diagnostic) => fail(s"generated grammar rejected: $diagnostic")
    }
    input => interpreter.evaluate(input) == EvaluationResult.Success("")
  }

  describe("plain PEG with multiple virtual machine steps per input character") {
    it("preserves pushes and pops within one character and across characters") {
      for (width <- Seq(2, 4)) {
        val accepts = recognizer(s"phase_preserving_move_$width.peg")
        for (n <- 0 to 5) assert(accepts("a" * n) == (n * width == 4), s"width=$width n=$n")
        assert(!accepts("b"))
      }
    }

    it("keeps ordered choice committed when its first branch returns in another phase") {
      val accepts = recognizer("phase_ordered_choice.peg")
      assert(!accepts("ab"))
      assert(!accepts("aab"))
      assert(!accepts(""))
    }

    it("preserves recursive matching, repetition, and repetition commitment") {
      val balanced = recognizer("phase_balanced.peg")
      val repeated = recognizer("phase_repetition.peg")
      val greedy = recognizer("phase_greedy.peg")
      var words = Seq("")
      for (_ <- 0 to 6) {
        words.foreach { input =>
          val n = input.length / 2
          assert(balanced(input) == (input == "a" * n + "b" * n), input)
          assert(repeated(input) == (input.nonEmpty && input.last == 'b' &&
            input.dropRight(1).forall(_ == 'a')), input)
          assert(!greedy(input), input)
        }
        words = words.flatMap(word => Seq(word + "a", word + "b"))
      }
    }
  }
}
