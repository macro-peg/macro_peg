package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.{Success, Failure}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class LambdaEvalSpec extends AnyFunSpec with Diagrams {
  describe("Lambda expression evaluation") {
    it("applies lambda as macro argument") {
      val grammar = Parser.parse(
        """S = Double((x -> x x), "aa") !.;
          |Double(f: ?, s: ?) = f(f(s));
        """.stripMargin)
      val evaluator = Evaluator(grammar)
      val resultSuccess = evaluator.evaluate("aaaaaaaa", Symbol("S"))
      val resultFailure = evaluator.evaluate("aaaa", Symbol("S"))
      assert(resultSuccess == Success(""))
      assert(resultFailure == Failure)
    }
  }
}

