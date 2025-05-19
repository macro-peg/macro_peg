package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class HigherOrderParserSpec extends AnyFunSpec with Diagrams {
  describe("Parser with typed arguments") {
    it("captures argument types") {
      val grammar = Parser.parse(
        """
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: ?, s: ?) = f(f(s));
        """.stripMargin)
      val doubleRule = grammar.rules.find(_.name == Symbol("Double")).get
      assert(doubleRule.argTypes.nonEmpty)
      assert(doubleRule.argTypes.size == 2)
      assert(doubleRule.argTypes.forall(_.isDefined))
    }
  }
}
