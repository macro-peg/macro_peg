package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.Success
import com.github.kmizu.macro_peg.Runner.evalGrammar
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class MacroPegCallByValueParSpec extends AnyFunSpec with Diagrams{
  describe("Macro PEG with call by value par example") {
    it("simple") {
      val results = evalGrammar(
        """
          |S = F("a"); F(A) = A A A;
     """.stripMargin,
        Seq("aaa"),
        EvaluationStrategy.CallByValuePar
      )
      assertResult(Seq(Success("")))(results)
    }

    it("xml") {
      val results = evalGrammar(
        """
          |S = "<" F([a-zA-Z_]+); F(N) = N ">" ("<" F([a-zA-Z_]+))* "</" N ">";
        """.stripMargin,
        Seq( "<a><b></b></a>"),
        EvaluationStrategy.CallByValuePar
      )
      assertResult(Seq(Success("")))(results)
    }
  }
}

