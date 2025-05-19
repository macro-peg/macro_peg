package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class TypeCheckerSpec extends AnyFunSpec with Diagrams {
  describe("TypeChecker") {
    it("rejects mismatched argument types") {
      val grammar = Parser.parse(
        """
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: ?, s: ?) = f(f(s));
        """.stripMargin)
      assertThrows[TypeChecker.TypeError] {
        TypeChecker.check(grammar)
      }
    }

    it("accepts correct function argument") {
      val grammar = Parser.parse(
        """
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: (?) -> ?, s: ?) = f(f(s));
        """.stripMargin)
      TypeChecker.check(grammar)
    }
  }
}
