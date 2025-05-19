package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.{Success, Failure}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class HigherOrderEvalSpec extends AnyFunSpec with Diagrams {
  describe("Higher-order macro evaluation") {
    it("evaluates nested macro application") {
      val grammar = Parser.parse(
        """S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: ?, s: ?) = f(f(s));
        """.stripMargin)
      val expanded = MacroExpander.expandGrammar(grammar)
      val evaluator = Evaluator(expanded)
      val resultSuccess = evaluator.evaluate("aaaaaaaa", Symbol("S"))
      val resultFailure = evaluator.evaluate("aaaa", Symbol("S"))
      assert(resultSuccess == Success(""))
      assert(resultFailure == Failure)
    }
  }
}
