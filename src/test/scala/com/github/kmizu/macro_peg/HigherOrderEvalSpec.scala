package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams
import com.github.kmizu.macro_peg.EvaluationResult.Success

class HigherOrderEvalSpec extends AnyFunSpec with Diagrams {
  describe("Higher order macro evaluation") {
    it("evaluates nested function calls") {
      val grammar = Parser.parse(
        """
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: (?) -> ?, s: ?) = f(f(s));
        """.stripMargin)
      TypeChecker.check(grammar)
      val evaluator = Evaluator(grammar)
      val result = evaluator.evaluate("aaaaaaaa", Symbol("S"))
      assert(result == Success(""))
    }
  }
}
