package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class TypeCheckerSpec extends AnyFunSpec with Diagrams {
  describe("TypeChecker") {
    it("accepts well typed higher order grammar") {
      val grammar = Parser.parse(
        """
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: (?)->?, s: ?) = f(f(s));
        """.stripMargin)
      assert(TypeChecker.wellTyped(grammar))
    }

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

    it("rejects invalid function usage") {
      val grammar = Parser.parse(
        """
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: (?)->?, s: ?) = f(s) f;
        """.stripMargin)
      assertThrows[TypeError] {
        TypeChecker.check(grammar)
      }
    }
  }
}
