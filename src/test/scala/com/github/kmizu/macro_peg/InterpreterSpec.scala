package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.Success
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class InterpreterSpec extends AnyFunSpec with Diagrams {
  describe("Interpreter") {
    it("rejects ill-typed grammar") {
      val grammar = """|
        |S = Double(Plus1, "aa") !.;
        |Plus1(s: ?) = s s;
        |Double(f: ?, s: ?) = f(f(s));
        |""".stripMargin
      assertThrows[TypeCheckException] {
        Interpreter.fromSource(grammar)
      }
    }

    it("evaluates after type checking") {
      val grammar = """|
        |S = Double(Plus1, "aa") !.;
        |Plus1(s: ?) = s s;
        |Double(f: ? -> ?, s: ?) = f(f(s));
        |""".stripMargin
      val interpreter = Interpreter.fromSource(grammar)
      val result = interpreter.evaluate("aaaaaaaa", Symbol("S"))
      assert(result == Success(""))
    }
  }
}
