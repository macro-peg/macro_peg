package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class HigherOrderReturnSpec extends AnyFunSpec with Diagrams {
  describe("TypeChecker for higher-order return") {
    it("infers returned function types") {
      val grammar = Parser.parse(
        """|
          |S = Apply(Baz((x -> x)), "a") !.;
          |Baz(f: ? -> ?) = f;
          |Apply(f: ? -> ?, s: ?) = f(s);
          |""".stripMargin)
      val checker = new TypeChecker(grammar)
      assert(checker.check().isRight)
    }
  }
}
