package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.Success
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class MacroPegSpec extends AnyFunSpec with Diagrams {
  describe("MacroPeg") {
    it("evaluates after type check") {
      val source =
        """|
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: (?) -> ?, s: ?) = f(f(s));
          |""".stripMargin
      val engine = MacroPeg.fromString(source, EvaluationStrategy.CallByName).getOrElse(fail("error"))
      val result = engine.evaluate("aaaaaaaa")
      assert(result == Success(""))
    }

    it("returns Left for type errors") {
      val invalid =
        """|
          |S = Double(Plus1, "aa") !.;
          |Plus1(s: ?) = s s;
          |Double(f: ?, s: ?) = f(f(s));
          |""".stripMargin
      assert(MacroPeg.fromString(invalid, EvaluationStrategy.CallByName).isLeft)
    }

    it("returns Left for parse errors") {
      assert(MacroPeg.fromString("S =", EvaluationStrategy.CallByName).isLeft)
    }
  }
}
