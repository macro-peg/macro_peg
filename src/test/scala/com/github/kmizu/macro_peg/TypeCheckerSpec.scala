package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class TypeCheckerSpec extends AnyFunSpec with Diagrams {
  describe("TypeChecker") {
    it("rejects ill typed higher order grammar") {
      val grammar = Parser.parse(
        """
          |S = Double("aa", "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: (?)->?, s: ?) = f(f(s));
        """.stripMargin)
      assert(!TypeChecker.wellTyped(grammar))
      TypeChecker.check(grammar)
    }

    it("accepts correct types") {
          |Double(f: (?)->?, s: ?) = f(f(s));
        """.stripMargin)
      TypeChecker.check(grammar)
    }
  }
}
