package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.Success
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class InterpreterDiagnosticsSpec extends AnyFunSpec with Diagrams {
  describe("Interpreter diagnostics API") {
    it("returns typed diagnostics for type errors") {
      val grammar = """|
        |S = Double(Plus1, "aa") !.;
        |Plus1(s: ?) = s s;
        |Double(f: ?, s: ?) = f(f(s));
        |""".stripMargin

      val result = Interpreter.fromSourceEither(grammar)
      assert(result.isLeft)
      assert(result.left.toOption.get.phase == DiagnosticPhase.TypeCheck)
    }

    it("returns evaluation diagnostics with expected token and snippet") {
      val interpreter = Interpreter.fromSource("S = \"ab\";")
      val result = interpreter.evaluateEither("ac")
      assert(result.isLeft)
      val diag = result.left.toOption.get
      assert(diag.phase == DiagnosticPhase.Evaluation)
      assert(diag.expected.nonEmpty)
      assert(diag.snippet.nonEmpty)
    }

    it("keeps existing evaluate behavior") {
      val interpreter = Interpreter.fromSource("S = \"ab\";")
      assert(interpreter.evaluate("ab") == Success(""))
    }

    it("produces same success with and without memoization") {
      val grammar = Parser.parse("S = \"a\"+ !.;")
      val evaluator = Evaluator(grammar)
      val memo = evaluator.evaluateWithDiagnostics("aaa", Symbol("S"))
      val noMemo = evaluator.evaluateWithoutMemo("aaa", Symbol("S"))
      assert(memo == noMemo)
    }
  }
}
