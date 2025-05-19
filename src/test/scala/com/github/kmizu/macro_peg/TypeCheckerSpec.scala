package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class TypeCheckerSpec extends AnyFunSpec with Diagrams {
  describe("TypeChecker") {
    it("rejects wrong argument types") {
      val grammar = Parser.parse(
        """
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: ?, s: ?) = f(f(s));
        """.stripMargin)
      val checker = new TypeChecker(grammar)
      assert(checker.check().isLeft)
    }

    it("accepts correct types") {
          |Double(f: (?)->?, s: ?) = f(f(s));
        """.stripMargin)
      TypeChecker.check(grammar)
    }
  }
}
